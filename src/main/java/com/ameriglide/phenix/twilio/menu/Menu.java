package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.common.Call;
import com.ameriglide.phenix.common.Leg;
import com.ameriglide.phenix.core.Log;
import com.ameriglide.phenix.core.Optionals;
import com.ameriglide.phenix.core.Strings;
import com.ameriglide.phenix.core.Suppliers;
import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.BadRequestException;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
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

import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.IGNORE;
import static com.ameriglide.phenix.servlet.TwiMLServlet.Op.THROW;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

@WebServlet("/twilio/menu/*")
public class Menu extends TwiMLServlet {
  private static final Supplier<Map<String, Step>> menus = Suppliers.memoize(() -> {
    var steps = new HashMap<String, Step>(2);
    steps.put("main", new Main());
    steps.put("customer", new Customer());
    return steps;

  });
  private static final Function<String, Optional<Matcher>> matcher = Strings.matcher(
    Pattern.compile("/twilio/menu/(.*)"));
  private static final Log log = new Log();

  public Menu() {
    super(method -> new Config(THROW, IGNORE));
  }

  public static VoiceResponse enter(final String step, final HttpServletRequest request,
                                    final HttpServletResponse response) {
    log.debug(() -> "%s Entering menu %s".formatted(request.getParameter("CallSid"), step));
    var steps = menus.get();
    return Optionals
      .of(steps.get(step))
      .orElseThrow(() -> new NotFoundException("Could not find menu " + step))
      .gather(request, response);
  }

  @Override
  protected void post(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    var steps = menus.get();
    var m = matcher
      .apply(request.getRequestURI())
      .orElseThrow(() -> new BadRequestException("request did not match /twilio/menu/.*"));
    var s = m.group(1);
    var step = Optionals.of(steps.get(s)).orElseThrow(() -> new NotFoundException("no menu for " + s));
    respond(response, step.process(request, response, call));
  }

  @Override
  protected void get(final HttpServletRequest request, final HttpServletResponse response, Call call, Leg leg) throws
    Exception {
    response.sendError(SC_NO_CONTENT);
  }

  public abstract static class Step {

    abstract VoiceResponse gather(HttpServletRequest request, HttpServletResponse response);

    abstract VoiceResponse process(HttpServletRequest request, HttpServletResponse response, Call call);

  }
}
