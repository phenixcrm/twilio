package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Party;
import com.ameriglide.phenix.common.ProductLine;
import com.ameriglide.phenix.common.Source;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.twilio.voice.Inbound;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Redirect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.potion.Locator;

import static com.ameriglide.phenix.servlet.Startup.router;
import static com.ameriglide.phenix.servlet.TwiMLServlet.pause;
import static com.ameriglide.phenix.servlet.TwiMLServlet.speak;

public class Main extends Menu.Step {

  private static final Log log = new Log();

  @Override
  public VoiceResponse gather(HttpServletRequest request, HttpServletResponse response) {
    log.trace(() -> "%s Gathering main menu input".formatted(request.getParameter("CallSid")));
    return new VoiceResponse.Builder()
      .gather(new Gather.Builder()
        .action(router.getApi("/menu/main"))
        .pause(pause(3))
        .numDigits(1)
        .say(speak("Thank you for calling AmeraGlide, your headquarters for Home Safety."))
        .pause(pause(1))
        .say(speak("If you are an existing customer, press 1, otherwise, please hold while we connect you to "
          + "our team of mobility specialists."))
        .build())
      .redirect(new Redirect.Builder(router.getApi("/menu/main")).build())
      .build();
  }

  @Override
  public VoiceResponse process(HttpServletRequest request, HttpServletResponse response, Call call) {
    var digits = Optionals.of(request.getParameter("Digits")).orElse("0");
    var caller = Party.fromRequest(request, "Caller");

    var builder = new VoiceResponse.Builder();

    switch (digits) {
      case "1" -> {
        return Menu.enter("customer", request, response);
      }
      default -> {
        Locator.update(call, "Main", copy -> {
          builder.say(TwiMLServlet.speak(
            "Did you know? AmeraGlide is quickly becoming the recognized leader in the mobility"
              + " and accessibility industry. We have the largest selection of products to ensure our customers get "
              + "the best"
              + " solution, at the lowest possible price. We guarantee it! Ask your mobility specialist about our 110% "
              + "guarantee!"));
          Inbound.enqueue(builder, caller, copy, router.getQueue("customerService"),
            ProductLine.undetermined.get(),Source.PHONE);
        });
        return builder.build();
      }
    }
  }
}
