package com.ameriglide.phenix.twilio.menu;

import com.ameriglide.phenix.servlet.TwiMLServlet;
import com.ameriglide.phenix.servlet.exception.BadRequestException;
import com.ameriglide.phenix.servlet.exception.NotFoundException;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Pause;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.Funky;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@WebServlet("/twilio/menu/*")
public class Menu extends TwiMLServlet {
  public abstract static class Step {
    public Step(Menu menu) {
      this.menu = menu;
    }

    protected final  Menu menu;
    abstract VoiceResponse gather(HttpServletRequest request, HttpServletResponse response);
    abstract VoiceResponse process(HttpServletRequest request, HttpServletResponse response);
  }
  public static VoiceResponse enter(final String step, final HttpServletRequest request,
                                    final HttpServletResponse response) {
    return Funky.of(steps.get(step))
      .orElseThrow(()->new NotFoundException("Could not find menu " + step))
      .gather(request, response);
  }



  private static Function<String,Optional<Matcher>> matcher =
    Funky.matcher(Pattern.compile("/twilio/menu/(.*)"));
  private static final Map<String, Step> steps = new HashMap<>();

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    steps.put("main", new Main(this));
    steps.put("customer", new Customer(this));
  }

  @Override
  public void destroy() {
    super.destroy();
    steps.clear();
  }

  public static final Pause PAUSE_1S = new Pause.Builder().length(1).build();

  @Override
  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    var m = matcher.apply(request.getRequestURI()).orElseThrow(()->
      new BadRequestException("request did not match /twilio/menu/.*"));
    var s = m.group(1);
    var step = Funky.of(steps.get(s)).orElseThrow(()->new NotFoundException("no menu for " + s));
    return step.process(request,response);
  }

  @Override
  protected TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception {
    return null;
  }
}
