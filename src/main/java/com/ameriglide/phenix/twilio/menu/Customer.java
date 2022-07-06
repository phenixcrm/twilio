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

import static com.ameriglide.phenix.servlet.TwiMLServlet.toVoicemail;

public class Customer extends Menu.Step {
  public Customer(Menu menu) {
    super(menu);
  }

  @Override
  VoiceResponse gather(HttpServletRequest request, HttpServletResponse response) {
    return new VoiceResponse.Builder()
      .gather(new Gather.Builder()
        .action("/twilio/menu/customer")
        .numDigits(1)
        .build())
      .say(TwiMLServlet.speak("Thank you for choosing AmeraGlide! If you have questions about an existing order, " +
        "or need assistance with shipping or tracking information, please press 1. If you require technical support " +
        "or need assistance with installation, please press 2. For all other inquiries, please press 3.", 2))
      .build();
  }

  @Override
  VoiceResponse process(HttpServletRequest request, HttpServletResponse response) {
    var builder = new VoiceResponse.Builder();
    var callSid = request.getParameter("CallSid");
    var caller = TwiMLServlet.asParty(request, "Caller");
    var call = Locator.$(new Call(callSid));
    if (call == null) {
      throw new NotFoundException("No call found for " + callSid);
    }
    switch (request.getParameter("Digits")) {
      case "1", "3" -> VoiceCall.enqueue(builder, caller, call,
        Locator.$(new WorkflowAssignment(CallType.SERVICE)).getQueue(), Source.PHONE);
      case "2" -> VoiceCall.enqueue(builder, caller, call,
        Locator.$(new WorkflowAssignment(CallType.SUPPORT)).getQueue(), Source.PHONE);
      default -> {
        return toVoicemail;
      }
    }
    return builder.build();
  }
}
