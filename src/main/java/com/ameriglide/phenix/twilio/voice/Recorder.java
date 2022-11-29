package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.servlet.Startup;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Record.Builder;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.util.List;
import java.util.Map;

import static com.ameriglide.phenix.core.Strings.isNotEmpty;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.twilio.http.HttpMethod.GET;
import static com.twilio.twiml.voice.Record.RecordingEvent.ABSENT;
import static com.twilio.twiml.voice.Record.RecordingEvent.COMPLETED;
import static com.twilio.twiml.voice.Record.Trim.TRIM_SILENCE;

@WebServlet(value = "/twilio/voice/record", loadOnStartup = 1)
public class Recorder extends TwiMLServlet {
  private static final Log log = new Log();
  public static Builder voicemail;

  public Recorder() {
    super(method -> new Config(CREATE, IGNORE));
  }

  public static Dial.Builder dial() {
    return dial(null);
  }

  public static Dial.Builder dial(final Call update) {
    var callback = Startup.router.getApi("/voice/record", update==null ? null:Map.of("update", update.sid));
    return new Dial.Builder()
      .record(Dial.Record.RECORD_FROM_ANSWER_DUAL)
      .trim(Dial.Trim.TRIM_SILENCE)
      .recordingStatusCallbackMethod(GET)
      .recordingStatusCallback(callback)
      .recordingStatusCallbackEvents(List.of(Dial.RecordingEvent.COMPLETED, Dial.RecordingEvent.ABSENT));
  }

  public static Conference.Builder conference(final String reservation, final Call update) {
    return new Conference.Builder(reservation)
      .record(Conference.Record.RECORD_FROM_START)
      .recordingStatusCallbackMethod(GET)
      .recordingStatusCallback(Startup.router.getApi("/voice/record", Map.of("update", update.sid)))
      .trim(Conference.Trim.TRIM_SILENCE)
      .recordingStatusCallbackEvents(List.of(Conference.RecordingEvent.COMPLETED, Conference.RecordingEvent.ABSENT));

  }


  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, final Call call,
                      final Leg leg) {

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
      case "absent" -> log.debug(() -> "transcription failed for %s".formatted(call.sid));
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
            copy.setRecordingSid(recording);
          });
        }
      }
    }

  }

  @Override
  protected String getCallSid(final HttpServletRequest request) {
    var update = request.getParameter("update");
    return Strings.isEmpty(update) ? super.getCallSid(request):update;
  }

  @Override
  public void destroy() {
    voicemail = null;
  }

  @Override
  public void init() throws ServletException {
    super.init();
    var callback = Startup.router.getApi("/voice/record");
    voicemail = new Builder()
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
