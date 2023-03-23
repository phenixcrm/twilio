package com.ameriglide.phenix.twilio.voice;

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

import static com.ameriglide.phenix.core.Strings.isEmpty;
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
    var agentId = request.getParameter("agent");
    var number = request.getParameter("number");
    if (isEmpty(agentId)) {
      if (isEmpty(number)) {
        throw new IllegalArgumentException("Must provide agent or number parameter");
      } else {
        var vCid = Locator.$1(VerifiedCallerId.isDefault);
        respond(response, new VoiceResponse.Builder()
          .dial(Status.watch(new Party(new PhoneNumber(number))).callerId(vCid.getPhoneNumber()).build())
          .hangup(hangup)
          .build());
      }
    } else {
      var agent = Locator.$(new Agent(Integer.parseInt(agentId)));
      if (agent==null) {
        throw new NotFoundException("could not find agent with id [%s]".formatted(agentId));
      }
      var worker = Startup.router.getWorker(agent.getSid());
      if (worker==null) {
        throw new NotFoundException("could not find worker for agent [%d] with sid [%s]".formatted(agent.id, agentId));
      }

      if (WorkerState.from(worker) == WorkerState.AVAILABLE) {
        respond(response, new VoiceResponse.Builder()
          .dial(Status.watch(new Party(agent)).build())
          .redirect(
            toVoicemail("%s is not available. Please leave a message and your call will be returned as soon as possible"))
          .build());
      } else {
        respond(response, new VoiceResponse.Builder()
          .redirect(
            toVoicemail("%s is not available. Please leave a message and your call will be returned as soon as possible"))
          .build());

      }
    }
  }
}
