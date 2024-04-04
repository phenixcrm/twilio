package com.ameriglide.phenix.twilio.voice;

import com.ameriglide.phenix.DialMode;
import com.ameriglide.phenix.common.*;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.twilio.Startup;
import com.ameriglide.phenix.types.WorkerState;
import com.twilio.twiml.VoiceResponse;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.CREATE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.THROW;

@WebServlet("/twilio/voice/transfer")
public class Transfer extends TwiMLServlet {
  public Transfer() {
    super(method -> new Config(THROW, CREATE));
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var param = DialMode.fromRequest(request);
    var agentId = param.value();
    switch (param.mode()) {
      case AGENT -> {
        var agent = Locator.$(new Agent(Integer.parseInt(agentId)));
        if (agent==null) {
          throw new NotFoundException("could not find agent with id [%s]".formatted(agentId));
        }
        var worker = Startup.router.getWorker(agent.getSid());
        if (worker==null) {
          throw new NotFoundException(
            "could not find worker for agent [%d] with sid [%s]".formatted(agent.id, agentId));
        }
        if (WorkerState.from(worker)==WorkerState.AVAILABLE) {
          respond(response, new VoiceResponse.Builder()
            .dial(Status.watch(new Party(agent)).build())
            .redirect(toVoicemail(
              "%s is not available. Please leave a message and your call will be returned as soon as possible"))
            .build());
        } else {
          respond(response, new VoiceResponse.Builder()
            .redirect(toVoicemail(
              "%s is not available. Please leave a message and your call will be returned as soon as possible"))
            .build());
        }
      }
      case NUMBER -> {
        var vCid = Locator.$1(VerifiedCallerId.isDefault);
        var number = param.value();
        respond(response, new VoiceResponse.Builder()
          .dial(Status.watch(new Party(new PhoneNumber(number))).callerId(vCid.getPhoneNumber()).build())
          .hangup(hangup)
          .build());
      }
      case QUEUE -> {
        var skillQueue = Locator.$(new SkillQueue(Integer.parseInt(param.value())));
        var toQueue = new VoiceResponse.Builder();
        var caller = Party.fromRequest(request, "Caller");
        Locator.update(call, "Transfer", copy -> {
          Inbound.enqueue(toQueue, caller, call, skillQueue, call.getProductLine(), call.getSource());
        });
        respond(response, toQueue.build());
      }
    }
  }
}
