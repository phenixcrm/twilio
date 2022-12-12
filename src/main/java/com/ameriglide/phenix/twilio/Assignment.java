package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.AgentStatus;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Opportunity;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.servlet.Startup;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.json.JsonMap;

import static com.ameriglide.phenix.servlet.Startup.shared;
import static com.ameriglide.phenix.servlet.Startup.topics;
import static net.inetalliance.potion.Locator.update;

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
    var reservation = request.getParameter("ReservationSid");
    var task = request.getParameter("TaskSid");
    var agent = Locator.$1(Agent.withSid(request.getParameter("WorkerSid")));
    if (Locator.find(Call.isActiveVoiceCall, c -> agent.equals(c.getActiveAgent()))!=null) {
      log.info(() -> "ASSIGN skipping %s because the agent is on a call".formatted(agent.getFullName()));
      PhenixServlet.respond(response, new JsonMap().$("instruction", "reject"));
      return;

    }
    var attributes = JsonMap.parse(request.getParameter("TaskAttributes"));
    var callSid = Optionals.of(attributes.get("call_sid")).orElse(task);
    log.info(() -> "ASSIGN %s %s to %s, Reservation=%s".formatted(callSid, attributes.get("caller"),
      agent.getFullName(),reservation));
    var call = Locator.$(new Call(callSid));
    update(call, "Assignment", copy -> {
      copy.setAgent(agent);
      copy.setBlame(agent);
    });
    if (attributes.containsKey("VoiceCall")) {
      Startup.router.voiceTask(task, reservation, new PhoneNumber(attributes.get("caller")), agent, callSid);
      response.sendError(200);
    } else {
      var opp = attributes.containsKey("Lead") ? Locator.$(
        new Opportunity(Integer.valueOf(attributes.get("Lead")))):call.getOpportunity();
      if (opp==null) {
        log.error(() -> "Could not find opp for assignment: %s".formatted(attributes));
      } else if (Agent.system().id.equals(opp.getAssignedTo().id)) {
        update(opp, "Assignment", copy -> {
          copy.setAssignedTo(agent);
        });
      }
      PhenixServlet.respond(response, new JsonMap().$("instruction", "accept"));
    }
    pop(agent, callSid);
    notify(call);
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
