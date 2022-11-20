package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.Startup;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Record.Builder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.net.URI;
import java.util.List;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Mode.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Mode.IGNORE;
import static com.ameriglide.phenix.types.Resolution.VOICEMAIL;
import static com.twilio.http.HttpMethod.GET;
import static com.twilio.twiml.voice.Record.RecordingEvent.ABSENT;
import static com.twilio.twiml.voice.Record.RecordingEvent.COMPLETED;
import static com.twilio.twiml.voice.Record.Trim.TRIM_SILENCE;

@WebServlet(value = "/twilio/voice/record", loadOnStartup = 1)
public class Recorder extends TwiMLServlet {
  private static final Log log = new Log();
  public static Builder watcher;
  private static URI callback;

  public Recorder() {
    super(CREATE, IGNORE);
  }

  public static Dial.Builder watch() {
    return new Dial.Builder()
      .record(Dial.Record.RECORD_FROM_ANSWER_DUAL)
      .trim(Dial.Trim.TRIM_SILENCE)
      .recordingStatusCallbackMethod(GET)
      .recordingStatusCallback(callback)
      .recordingStatusCallbackEvents(List.of(Dial.RecordingEvent.COMPLETED, Dial.RecordingEvent.ABSENT));
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) throws Exception {

    // transcription
    switch (request.getParameter("TranscriptionStatus")) {
      case "completed" -> {
        var transcription = request.getParameter("TranscriptionText");
        if (isNotEmpty(transcription)) {
          log.debug(() -> "added transcription text for %s = %s".formatted(call.sid, transcription));
          Locator.update(call, "Record", copy -> {
            copy.setTranscription(transcription);
          });
        }
      }
      case "absent" -> {
        log.debug(() -> "transcription failed for %s".formatted(call.sid));
      }
    }
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                     final Leg leg) throws Exception {
    // audio recordfing
    switch (request.getParameter("RecordingStatus")) {
      case "completed" -> {
        var recording = request.getParameter("RecordingSid");
        if (isNotEmpty(recording)) {
          log.debug(
            () -> "added recording for %s (%s sec)".formatted(call.sid, request.getParameter("RecordingDuration")));
          Locator.update(call, "Record", copy -> {
            copy.setResolution(VOICEMAIL);
            copy.setRecordingSid(recording);
          });
        }
      }
    }

  }

  @Override
  public void destroy() {
    watcher = null;
    callback = null;
  }

  @Override
  public void init() throws ServletException {
    super.init();
    callback = Startup.router.getAbsolutePath("/twilio/voice/record", null);
    watcher = new Builder()
      .recordingStatusCallbackMethod(GET)
      .recordingStatusCallback(callback)
      .recordingStatusCallbackEvents(List.of(COMPLETED, ABSENT))
      .trim(TRIM_SILENCE)
      .playBeep(true)
      .transcribe(true)
      .transcribeCallback(callback)
      .maxLength(300);
  }
}
