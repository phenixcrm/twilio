package com.ameriglide.phenix.twilio.voice.task;

import com.ameriglide.phenix.common.Agent;
import jakarta.servlet.http.HttpServletRequest;
import net.inetalliance.potion.Locator;

import java.util.Map;

public record Params(String task, String reservation, Agent agent, String connect) {
  public Params(HttpServletRequest request) {
    this(request.getParameter("task"), request.getParameter("reservation"),
      Locator.$(new Agent(Integer.parseInt(request.getParameter("agent")))), request.getParameter("connect"));
  }

  public Map<String,Object> toNamedValues() {
    return Map.of("task",task,"reservation",reservation,"agent",agent.id,"connect",connect);
  }
}
