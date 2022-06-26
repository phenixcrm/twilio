package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.log.Log;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

@WebServlet("/twilio/assignment")
public class Assignment extends PhenixServlet {
  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
    var attributes = JsonMap.parse(request.getParameter("TaskAttributes"));
    var callSid = attributes.get("call_sid");
    log.info("ASSIGN %s %s", callSid, attributes.get("caller"), agent.getFullName());
    var reservation = request.getParameter("ReservationSid");
    Startup.router.conference(callSid, reservation);
    PhenixServlet.respond(response, new JsonMap()
      .$("instruction", "call")
      .$("timeout", 15)
      .$("record", "record-from-answer")
      .$("url",
        Startup.router.getAbsolutePath("/twilio/voice/callAgent",
          "ReservationSid=%s&Assignment=%s".formatted(reservation,callSid)).toString())
      .$("statusCallbackUrl", Startup.router.getAbsolutePath("/twilio/voice/callAgent", null).toString())
      .$("to", TwiMLServlet.asParty(agent).sip()));
  }

  private static final Log log = Log.getInstance(Assignment.class);
}
