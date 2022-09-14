package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Source;
import com.ameriglide.phenix.common.WorkflowAssignment;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.ameriglide.phenix.twilio.VoiceCall;
import com.ameriglide.phenix.types.CallType;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static com.ameriglide.phenix.servlet.TwiMLServlet.*;

public class Customer extends Menu.Step {
    public Customer() {
        super();
    }

    @Override
    VoiceResponse gather(HttpServletRequest request, HttpServletResponse response) {
        var now = LocalDateTime.now();
        if (now.getDayOfWeek()==DayOfWeek.SUNDAY || now.getHour() < 8 || now.isAfter(now.withHour(21))) {
            return new VoiceResponse.Builder()
                    .say(speak("We are currently closed. Our business hours are Monday through Saturday, 8AM "
                            + "until 9PM eastern standard time. Please leave a message, and we will return your call "
                            + "promptly "
                            + "as soon as we open"))
                    .redirect(toVoicemail)
                    .build();
        }
        return new VoiceResponse.Builder()
                .gather(new Gather.Builder()
                        .action("/twilio/menu/customer")
                        .numDigits(1)
                        .say(speak("Thank you for choosing AmeraGlide! If you have questions about an existing order, "
                                        + "or need assistance with shipping or tracking information, please press 1. "
                                        + "If you require technical support "
                                        + "or need assistance with installation, please press 2. For all other "
                                        + "inquiries, please press 3.",
                                2))
                        .build())
                .redirect(toVoicemail)
                .build();
    }

    @Override
    VoiceResponse process(HttpServletRequest request, HttpServletResponse response) {
        var builder = new VoiceResponse.Builder();
        var callSid = request.getParameter("CallSid");
        var caller = asParty(request, "Caller");
        var call = Locator.$(new Call(callSid));
        if (call==null) {
            throw new NotFoundException("No call found for " + callSid);
        }
        switch (request.getParameter("Digits")) {
            case "1", "3" -> VoiceCall.enqueue(builder, caller, call,
                    Locator.$(new WorkflowAssignment(CallType.SERVICE)).getQueue(), Source.PHONE);
            case "2" -> VoiceCall.enqueue(builder, caller, call,
                    Locator.$(new WorkflowAssignment(CallType.SUPPORT)).getQueue(), Source.PHONE);
            default -> {
                return new VoiceResponse.Builder().redirect(toVoicemail).build();
            }
        }
        return builder.build();
    }
}
