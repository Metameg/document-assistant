package com.example.documentassistant.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Validate Access at the origin too: knowing the Railway hostname must not bypass login. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name="document-assistant.access.enabled", havingValue="true")
public final class CloudflareAccessFilter extends OncePerRequestFilter {
  private final JwtDecoder decoder;
  private final byte[] internalToken;
  private static final Set<String> WORKER_ENDPOINTS=Set.of("/api/rag/answer","/api/rag/evidence-pool");

  @Autowired
  public CloudflareAccessFilter(@Value("${document-assistant.access.team-domain}") String team,
      @Value("${document-assistant.access.audience}") String audience,
      @Value("${document-assistant.access.internal-token}") String internalToken) {
    this(decoder(team,audience),internalToken);
  }
  CloudflareAccessFilter(JwtDecoder decoder,String internalToken) {
    if(internalToken==null || internalToken.length()<32) throw new IllegalArgumentException("Set a random internal token of at least 32 characters");
    this.decoder=decoder;this.internalToken=internalToken.getBytes(StandardCharsets.UTF_8);
  }
  private static JwtDecoder decoder(String team,String audience) {
    if(!team.matches("[a-zA-Z0-9-]+\\.cloudflareaccess\\.com") || audience.isBlank())
      throw new IllegalArgumentException("Configure the Cloudflare Access team domain and application audience");
    String issuer="https://"+team;
    NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(issuer+"/cdn-cgi/access/certs").build();
    decoder.setJwtValidator(validators(issuer,audience));
    return decoder;
  }
  static OAuth2TokenValidator<Jwt> validators(String issuer,String audience) {
    return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
        jwt->jwt.getAudience().contains(audience)?OAuth2TokenValidatorResult.success():
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Incorrect Access application audience",null)));
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String path=request.getRequestURI();
    if("GET".equals(request.getMethod()) && "/actuator/health".equals(path)) {chain.doFilter(request,response);return;}
    String supplied=request.getHeader("X-Document-Assistant-Worker");
    boolean loopback=Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr());
    if(loopback && "POST".equals(request.getMethod()) && WORKER_ENDPOINTS.contains(path) && supplied!=null
        && MessageDigest.isEqual(internalToken,supplied.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request,response);return;
    }
    String token=request.getHeader("Cf-Access-Jwt-Assertion");
    if((token==null || token.isBlank()) && request.getCookies()!=null)
      for(Cookie cookie:request.getCookies()) if(cookie.getName().equals("CF_Authorization")) {token=cookie.getValue();break;}
    try {
      if(token==null || token.isBlank()) throw new BadJwtException("Missing Access token");
      decoder.decode(token);
    } catch(JwtException | IllegalArgumentException e) {
      response.setStatus(401);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
      response.getWriter().write("{\"status\":401,\"detail\":\"Sign in through the private Cloudflare Access hostname.\"}");return;
    }
    // Cookies can authenticate browser requests: reject cross-site mutations.
    if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())
        && "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {response.sendError(403);return;}
    if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) && request.getHeader("Origin")!=null) {
      try {
        if(!request.getHeader("Host").equalsIgnoreCase(java.net.URI.create(request.getHeader("Origin")).getRawAuthority())) {
          response.sendError(403);return;
        }
      } catch(IllegalArgumentException | NullPointerException e) {response.sendError(403);return;}
    }
    chain.doFilter(request,response);
  }
}
