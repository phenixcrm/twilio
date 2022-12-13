package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.AgentStatus;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.PhenixServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.types.json.JsonMap;

import static com.ameriglide.phenix.servlet.Startup.shared;
import static com.ameriglide.phenix.servlet.Startup.topics;

@WebServlet("/twilio/assignment")
public class Assignment extends PhenixServlet {
  private static final Log log = new Log();

  public Assignment() {
  }

  public static void clear(final Agent agent) {
    if (agent!=null) {
      var status = shared.availability().get(agent.id);
      shared.availability().put(agent.id, status==null ? new AgentStatus(agent):status.withCall(null));
      topics
        .events()
        .publishAsync(
          new JsonMap().$("agent", agent.id).$("type", "status").$("event", new JsonMap().$("clear", true)));
    }
  }

  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {

  }

  public static void pop(Agent agent, String callSid) {
    topics
      .events()
      .publishAsync(new JsonMap().$("agent", agent.id).$("type", "pop").$("event", new JsonMap().$("callId", callSid)));
  }

  public static void notify(final Call call) {
    var agent = call.getAgent();
    var status = shared.availability().get(agent.id);
    shared.availability().put(agent.id, status==null ? new AgentStatus(agent, call):status.withCall(call));
    topics
      .events()
      .publishAsync(
        new JsonMap().$("agent", agent.id).$("type", "status").$("event", new JsonMap().$("call", call.sid)));
  }
}
