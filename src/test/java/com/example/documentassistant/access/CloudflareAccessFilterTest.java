package com.example.documentassistant.access;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import java.time.Instant;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class CloudflareAccessFilterTest {
  final String issuer="https://example.cloudflareaccess.com",audience="private-app",worker="a-random-test-worker-token-of-at-least-32-characters";
  RSAKey key;
  CloudflareAccessFilter filter;
  @BeforeEach void setup() throws Exception {
    key=new RSAKeyGenerator(2048).generate();
    var decoder=NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
    decoder.setJwtValidator(CloudflareAccessFilter.validators(issuer,audience));filter=new CloudflareAccessFilter(decoder,worker);
  }
  String token(String tokenIssuer,String tokenAudience,Instant expiry,RSAKey signingKey) throws Exception {
    var claims=new JWTClaimsSet.Builder().issuer(tokenIssuer).audience(tokenAudience).subject("owner")
        .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(expiry)).build();
    var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);jwt.sign(new RSASSASigner(signingKey));return jwt.serialize();
  }
  String valid() throws Exception {return token(issuer,audience,Instant.now().plusSeconds(300),key);}
  int request(String method,String path,String token,String address,String internal) throws Exception {
    var request=new MockHttpServletRequest(method,path);request.setRemoteAddr(address);
    if(token!=null)request.addHeader("Cf-Access-Jwt-Assertion",token);
    if(internal!=null)request.addHeader("X-Document-Assistant-Worker",internal);
    var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->((MockHttpServletResponse)s).setStatus(204));return response.getStatus();
  }
  @Test void protectsTheDirectRailwayUrlAndAcceptsASignedAccessToken() throws Exception {
    assertEquals(401,request("GET","/",null,"203.0.113.1",null));
    assertEquals(401,request("DELETE","/api/corpora/private",null,"203.0.113.1",null));
    assertEquals(204,request("GET","/",valid(),"203.0.113.1",null));
  }
  @Test void rejectsWrongApplicationIssuerExpiryAndSignature() throws Exception {
    assertEquals(401,request("GET","/",token(issuer,"another-app",Instant.now().plusSeconds(300),key),"203.0.113.1",null));
    assertEquals(401,request("GET","/",token("https://other.cloudflareaccess.com",audience,Instant.now().plusSeconds(300),key),"203.0.113.1",null));
    assertEquals(401,request("GET","/",token(issuer,audience,Instant.now().minusSeconds(300),key),"203.0.113.1",null));
    assertEquals(401,request("GET","/",token(issuer,audience,Instant.now().plusSeconds(300),new RSAKeyGenerator(2048).generate()),"203.0.113.1",null));
  }
  @Test void limitsTheInternalSecretToLocalEvaluationCalls() throws Exception {
    assertEquals(204,request("POST","/api/rag/answer",null,"127.0.0.1",worker));
    assertEquals(204,request("POST","/api/rag/evidence-pool",null,"::1",worker));
    assertEquals(401,request("POST","/api/rag/answer",null,"203.0.113.1",worker));
    assertEquals(401,request("DELETE","/api/corpora/private",null,"127.0.0.1",worker));
    assertEquals(401,request("POST","/api/rag/answer",null,"127.0.0.1","wrong"));
    assertEquals(204,request("GET","/actuator/health",null,"203.0.113.1",null));
    assertEquals(401,request("GET","/actuator/env",null,"203.0.113.1",null));
  }
  @Test void acceptsTheAccessCookieAndRejectsCrossSiteMutations() throws Exception {
    var request=new MockHttpServletRequest("POST","/api/corpora");
    request.setCookies(new Cookie("CF_Authorization",valid()));request.addHeader("Sec-Fetch-Site","cross-site");
    var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Cross-site write reached controller"));assertEquals(403,response.getStatus());
    request=new MockHttpServletRequest("GET","/");request.setCookies(new Cookie("CF_Authorization",valid()));
    response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->((MockHttpServletResponse)s).setStatus(204));assertEquals(204,response.getStatus());
  }
}
