package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Source;
import com.ameriglide.phenix.common.WorkflowAssignment;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.twilio.VoiceCall;
import com.ameriglide.phenix.types.CallType;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.TwiMLServlet.speak;
import static com.ameriglide.phenix.twilio.menu.Menu.PAUSE_1S;

public class Main extends Menu.Step {
  public Main(Menu menu) {
    super(menu);
  }

  @Override
  public VoiceResponse gather(HttpServletRequest request, HttpServletResponse response) {
    return new VoiceResponse.Builder()
      .gather(new Gather.Builder()
        .action("/twilio/menu/main")
        .numDigits(1)
        .timeout(300)
        .build())
      .say(speak("Thank you for calling AmeraGlide, your headquarters for Home Safety."))
      .pause(PAUSE_1S)
      .say(speak("If you are an existing customer, press 1, otherwise, please hold while we connect you to " +
        "our team of mobility specialists."))
      .build();
  }

  @Override
  public VoiceResponse process(HttpServletRequest request, HttpServletResponse response) {
    var callSid = request.getParameter("CallSid");
    var caller = TwiMLServlet.asParty(request, "Caller");
    var call = Locator.$(new Call(callSid));
    if (call == null) {
      throw new NotFoundException("No call found for " + callSid);
    }
    var builder = new VoiceResponse.Builder();

    switch (request.getParameter("Digits")) {
      case "1" -> {
        return Menu.enter("customer", request, response);
      }
      default -> {
        Locator.update(call, "Main", copy -> {
          builder
            .say(TwiMLServlet.speak("Did you know? AmeraGlide is quickly becoming the recognized leader in the mobility"
              + " and accessibility industry. We have the largest selection of products to ensure our customers get the best"
              + " solution, at the lowest possible price. We guarantee it! Ask your mobility specialist about our 110% "
              + "guarantee!"));
          VoiceCall.enqueue(builder, caller, copy,
            Locator.$(new WorkflowAssignment(CallType.SALES)).getQueue(),
            Source.PHONE);
        });
        return builder.build();
      }
    }
  }
}
