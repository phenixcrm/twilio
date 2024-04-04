package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.common.ProductLine;
import com.ameriglide.phenix.common.Source;
import com.ameriglide.phenix.twilio.voice.Inbound;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.speak;
import static com.ameriglide.phenix.servlet.TwiMLServlet.toVoicemail;

public class Customer extends Menu.Step {

  public static final String badInput =
    "We were unable to determine your entry. Please leave a message and we will call back as "
      + "soon as possible";

  public Customer() {
    super();
  }

  @Override
  VoiceResponse gather(HttpServletRequest request, HttpServletResponse response) {
    var now = LocalDateTime.now();
    if (now.getDayOfWeek()==DayOfWeek.SUNDAY || now.getHour() < 8 || now.isAfter(now.withHour(21))) {
      return new VoiceResponse.Builder()
        .redirect(toVoicemail("We are currently closed. Our business hours are Monday through Saturday, 8AM "
          + "until 9PM eastern standard time. Please leave a message, and we will return your call "
          + "promptly as soon as we open"))
        .build();
    }
    return new VoiceResponse.Builder()
      .gather(new Gather.Builder()
        .action(router.getApi("/menu/customer"))
        .numDigits(1)
        .say(speak("Thank you for choosing AmeraGlide! If you have questions about an existing order, "
          + "or need assistance with shipping or tracking information, please press 1. "
          + "If you require technical support "
          + "or need assistance with installation, please press 2. For all other "
          + "inquiries, please press 3.", 2))
        .build())
      .redirect(toVoicemail(badInput))
      .build();
  }

  @Override
  VoiceResponse process(HttpServletRequest request, HttpServletResponse response, Call call) {
    var builder = new VoiceResponse.Builder();
    var caller = Party.fromRequest(request, "Caller");
    switch (request.getParameter("Digits")) {
      case "1", "3" -> Locator.update(call, "Customer", copy -> {
        Inbound.enqueue(builder, caller, copy, router.getQueue("customer-service"),
          ProductLine.undetermined.get(), Source.PHONE);
      });
      case "2" -> Locator.update(call, "Customer", copy -> {
        Inbound.enqueue(builder, caller, copy, router.getQueue("tech-support"), ProductLine.undetermined.get(),
          Source.PHONE);
      });
      default -> {
        return new VoiceResponse.Builder().redirect(toVoicemail(badInput)).build();
      }
    }
    return builder.build();
  }
}
