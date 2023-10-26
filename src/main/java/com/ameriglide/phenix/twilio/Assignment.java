package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.servlet.PhenixServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.types.json.JsonMap;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.ameriglide.phenix.servlet.Startup.topics;

@WebServlet("/twilio/assignment")
public class Assignment extends PhenixServlet {
  private static final Log log = new Log();

  public Assignment() {
  }

  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {

  }
  record Pop (String callSid, Integer agent) {
    @Override
    public boolean equals(final Object o) {
      if (this==o) {
        return true;
      }
      if (o==null || getClass()!=o.getClass()) {
        return false;
      }
      final Pop pop = (Pop) o;
      return Objects.equals(callSid, pop.callSid) && Objects.equals(agent, pop.agent);
    }
    Pop(String callSid, Agent agent) {
      this(callSid,agent.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(callSid, agent);
    }
  }
  static final Set<Pop> popped = new HashSet<>();

  public static void pop(Agent agent, String callSid) {
    if(popped.add(new Pop(callSid,agent))) {
      log.debug(() -> "popping %s for %s".formatted(callSid, agent.getFullName()));
      topics
        .events()
        .publishAsync(new JsonMap().$("agent", agent.id).$("type", "pop").$("event", new JsonMap().$("callId", callSid)));
    } else {
      log.debug(()->"not popping %s for %s, because we've done that already".formatted(callSid,agent.getFullName()));
    }
  }

  public static void notify(final Call call) {
    topics
      .events()
      .publishAsync(
        new JsonMap().$("agent", call.getAgent().id).$("type", "status").$("event", new JsonMap().$("call",
          call.sid)));
  }
}
