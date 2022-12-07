package com.ameriglide.phenix.twilio.voice.task;

import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.potion.Locator;

import java.util.Map;

public record Params(String task, String reservation, Agent agent, String connect, String xfer) {
  public Params(HttpServletRequest request) {
    this(request.getParameter("task"), request.getParameter("reservation"),
      Locator.$(new Agent(Integer.parseInt(request.getParameter("agent")))), request.getParameter("connect"),
      request.getParameter("xfer"));
  }

  public Map<String, Object> toNamedValues() {
    return Map.of("task", task, "reservation", reservation, "agent", agent.id, "connect", connect);
  }

  public String label() {
    return Optionals
      .of(xfer)
      .filter(Strings::isNotEmpty)
      .map(to -> "%dx%s".formatted(agent.id, xfer))
      .orElse(Integer.toString(agent.id));
  }
}
