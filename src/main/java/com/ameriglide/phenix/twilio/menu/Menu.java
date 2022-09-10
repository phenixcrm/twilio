package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.core.Suppliers;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.BadRequestException;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebServlet("/twilio/menu/*")
public class Menu extends TwiMLServlet {
  public abstract static class Step {

    abstract VoiceResponse gather(HttpServletRequest request, HttpServletResponse response);
    abstract VoiceResponse process(HttpServletRequest request, HttpServletResponse response);

  }

  private static final Supplier<Map<String,Step>> menus = Suppliers.memoize(()-> {
    var steps = new HashMap<String,Step>(2);
    steps.put("main", new Main());
    steps.put("customer", new Customer());
    return steps;

  });
  public static VoiceResponse enter(final String step, final HttpServletRequest request,
                                    final HttpServletResponse response) {
    log.info(()->"Entering menu %s".formatted(step));
    var steps = menus.get();
    return Optionals.of(steps.get(step))
      .orElseThrow(()->new NotFoundException("Could not find menu " + step))
      .gather(request, response);
  }



  private static final Function<String,Optional<Matcher>> matcher =
    Strings.matcher(Pattern.compile("/twilio/menu/(.*)"));

  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var steps = menus.get();
    var m = matcher.apply(request.getRequestURI()).orElseThrow(()->
      new BadRequestException("request did not match /twilio/menu/.*"));
    var s = m.group(1);
    var step = Optionals.of(steps.get(s)).orElseThrow(()->new NotFoundException("no menu for " + s));
    return step.process(request,response);
  }

  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) {
    return null;
  }

  private static final Log log = new Log();
}
