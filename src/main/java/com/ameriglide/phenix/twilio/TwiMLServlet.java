package com.ameriglide.phenix.twilio;

import com.ameriglide.phenix.servlet.PhenixServlet;
import com.ameriglide.phenix.common.Agent;
import com.ameriglide.phenix.common.CNAM;
import com.ameriglide.phenix.types.CallerId;
import com.twilio.http.HttpMethod;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Hangup;
import com.twilio.twiml.voice.Pause;
import com.twilio.twiml.voice.Redirect;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.funky.Funky;
import net.inetalliance.funky.StringFun;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.geopolitical.Country;
import net.inetalliance.types.geopolitical.us.State;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static java.lang.System.out;

public abstract class TwiMLServlet extends PhenixServlet {
  public static final Pause pause = new Pause.Builder().length(1).build();
  protected static final Hangup hangup = new Hangup.Builder().build();
  protected static final VoiceResponse toVoicemail = new VoiceResponse.Builder()
    .redirect(new Redirect.Builder("/twilio/voicemail")
      .method(HttpMethod.GET)
      .build())
    .build();

  protected Say speak(String msg) {
    return new Say.Builder(msg)
      .voice(Say.Voice.POLLY_SALLI_NEURAL)
      .build();

  }

  protected void info(final String format, Object... args) {
    if (format != null) {
      out.printf("%s\t%s\t%n%s%n",
        "TwiML",
        getClass().getSimpleName(),
        format.formatted(args));
    }
  }

  protected void error(final Throwable t) {
    if (t != null) {
      out.printf("%s\t%s\t%n%s%n",
        "TwiML",
        getClass().getSimpleName(),
        t.getClass().getSimpleName());
      t.printStackTrace(out);
    }
  }

  protected static void respond(final HttpServletResponse response, final TwiML twiml) throws IOException {
    if (twiml == null) {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } else {
      response.setContentType("text/xml");
      try (var writer = response.getWriter()) {
        writer.write(twiml.toXml());
        writer.flush();
      }
    }
  }

  private static final String bar = "\n==================================";
  private static final String line = "\n----------------------------------";

  @Override
  protected final void get(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var s = new StringBuilder(bar)
      .append("\nGET ")
      .append(request.getRequestURI())
      .append(line);
    request.getParameterMap().forEach((k, v) -> s.append("%n%s: %s".formatted(k, String.join(", ", v))));
    s.append(line);
    info(s.toString());
    var twiml = getResponse(request, response);
    if (twiml == null) {
      response.sendError(HttpServletResponse.SC_NO_CONTENT);
    } else {
      respond(response, twiml);
    }
  }

  @Override
  protected void post(HttpServletRequest request, HttpServletResponse response) throws Exception {
    var s = new StringBuilder(bar)
      .append("\nPOST ")
      .append(request.getRequestURI())
      .append(line);
    request.getParameterMap().forEach((k, v) -> s.append("%n%s: %s".formatted(k, String.join(", ", v))));
    try (var reader = request.getReader()) {
      var data = new StringBuilder("\nRequest Body:\n");
      String line;
      while ((line = reader.readLine()) != null) {
        data.append(line);
      }
      s.append(data);
    }
    s.append(line);
    info(s.toString());
    respond(response, postResponse(request, response));
  }

  protected TwiML postResponse(HttpServletRequest request, HttpServletResponse response) {
    return null;
  }

  protected abstract TwiML getResponse(HttpServletRequest request, HttpServletResponse response) throws Exception;

  private static final Predicate<String> e164 = Pattern.compile("^\\+[1-9]\\d{1,14}$").asMatchPredicate();

  protected static Party asParty(HttpServletRequest request, String prefix) {
    var name = request.getParameter(prefix + "Name");
    var city = request.getParameter(prefix + "City");
    var state = request.getParameter(prefix + "State");
    var zip = request.getParameter(prefix + "Zip");
    var country = request.getParameter(prefix + "Country");
    var sipUri = request.getParameter(prefix);
    var matcher = sip.matcher(sipUri);
    if (matcher.matches()) {
      var user = matcher.group(1);
      return new Party(user, isAgent.test(user), sipUri, name, city, state, zip, country);
    } else if (e164.test(sipUri)) {
      return new Party(sipUri, false, sipUri, name, city, state, zip, country);
    }
    throw new IllegalArgumentException();
  }

  protected static Party asParty(final PhoneNumber party) {
    return new Party(party.getEndpoint(), false, party.getEndpoint(), null, null, null, null, null);
  }

  protected static Party asParty(final Agent agent) {
    return new Party(agent.getSipUser(),
      true,
      "sip:%s@%s;transport=tls".formatted(agent.getSipUser(),
        "phenix.sip.twilio.com"), agent.getFullName(), null, null, null, null);
  }

  private static final Pattern sip = Pattern.compile("^sip:([A-Za-z\\d.]*)@([a-z]*).sip.twilio.com(;.*)?$");
  private static final Predicate<String> isAgent = Pattern.compile("[A-Za-z.]*").asMatchPredicate();

  private static final Function<String, Agent> lookup = Funky.memoize(32, (user) -> Locator.$1(Agent.withSipUser(user)));

  public record Party(String endpoint, boolean isAgent, String sip, String name, String city, String state, String zip,
                      String country) {
    Agent agent() {
      if (isAgent) {
        return lookup.apply(endpoint);
      }
      throw new IllegalStateException();
    }

    CallerId callerId() {
      if (isAgent) {
        var agent = agent();
        return new CallerId(agent.getFullName(), agent.getSipUser());
      }
      return new CallerId(name, endpoint);
    }

    public String spoken() {
      if (isAgent()) {
        var a = agent();
        return a == null ? endpoint : agent().getFullName();
      } else {
        return endpoint;
      }
    }

    public void setCNAM(CNAM cnam) {
      cnam.setName(name);
      cnam.setPhone(endpoint);
      cnam.setCity(city);
      if (StringFun.isNotEmpty(state)) {
        cnam.setState(State.fromAbbreviation(state));
      }
      if (StringFun.isNotEmpty(country)) {
        cnam.setCountry(Country.fromIsoA2(country));
      } else {
        cnam.setCountry(Country.UNITED_STATES);
      }
      cnam.setZip(zip);

    }

    public String sipCid() {
      if (StringFun.isEmpty(name)) {
        return endpoint;
      }
      var split = name.split("[,]", 2);
      if (split.length == 2) {
        return split[1].trim() + "_" + split[0].trim();
      }
      return split[0].trim();
    }
  }

}
