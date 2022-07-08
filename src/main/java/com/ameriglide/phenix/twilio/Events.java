package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.types.Resolution;
import com.twilio.twiml.TwiML;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@WebServlet("/twilio/events")
public class Events extends TwiMLServlet {
  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var task = JsonMap.parse(request.getParameter("TaskAttributes"));
    switch (request.getParameter("EventType")) {
      case "task.cancelled" -> {
        if(task.containsKey("VoiceCall")) {
          var call = Locator.$(new Call(task.get("VoiceCall")));
          log.info("%s cancelled (%s)", call.sid, request.getParameter("Reason"));
          Startup.router.sendToVoicemail(call.sid);
          Locator.update(call, "Events", copy -> {
            copy.setResolution(Resolution.VOICEMAIL);
          });
        } else if (task.containsKey("Lead")) {
          var call = Locator.$(new Call(request.getParameter("TaskSid")));
          Locator.update(call,"Events", copy-> {
            copy.setResolution(Resolution.DROPPED);
            copy.setBlame(Agent.system());
            copy.setDuration(ChronoUnit.SECONDS.between(copy.getCreated(), LocalDateTime.now()));
            copy.setTalkTime(0);
          });
        }
      }
    }
    return null;

  }

  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {
    return null;
  }

  private static final Log log = Log.getInstance(Events.class);
}
