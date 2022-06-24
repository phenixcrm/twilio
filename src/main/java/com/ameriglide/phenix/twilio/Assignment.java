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
    log.info("ASSIGN %s %s",attributes.get("call_sid"),attributes.get("caller"), agent.getFullName());
    PhenixServlet.respond(response, new JsonMap()
      .$("instruction","conference")
      .$("to", TwiMLServlet.asParty(agent).sip()));
  }
  private static final Log log = Log.getInstance(Assignment.class);
}
