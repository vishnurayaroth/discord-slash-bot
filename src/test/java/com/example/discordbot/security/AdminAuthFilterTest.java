package com.example.discordbot.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.support.ServletFakes.FakeChain;
import com.example.discordbot.support.ServletFakes.FakeRequest;
import com.example.discordbot.support.ServletFakes.FakeResponse;
import com.example.discordbot.support.ServletFakes.FakeSession;
import org.junit.jupiter.api.Test;

class AdminAuthFilterTest {

    private final AdminAuthFilter filter = new AdminAuthFilter();

    private static FakeSession signedInSession() {
        FakeSession s = new FakeSession();
        s.attributes.put(AdminAuthFilter.SESSION_ADMIN, Boolean.TRUE);
        return s;
    }

    private record Run(FakeChain chain, FakeResponse response) {}

    private Run run(FakeRequest request) throws Exception {
        FakeChain chain = new FakeChain();
        FakeResponse response = new FakeResponse();
        filter.doFilter(request.request(), response.response(), chain);
        return new Run(chain, response);
    }

    private static FakeRequest request(String method, String servletPath) {
        FakeRequest r = new FakeRequest();
        r.method = method;
        r.servletPath = servletPath;
        r.contextPath = "/app";
        return r;
    }

    @Test
    void aSignedOutPageRequestRedirectsToSignInAndReachesNothing() throws Exception {
        Run run = run(request("GET", "/dashboard"));
        assertEquals("/app/login", run.response().redirect);
        assertFalse(run.chain().called);
    }

    @Test
    void aSignedOutApiCallGets401JsonAndNeverARedirect() throws Exception {
        Run run = run(request("GET", "/api/log"));
        assertEquals(401, run.response().status);
        assertEquals("{\"error\":\"unauthorized\"}", run.response().body());
        assertNull(run.response().redirect);
        assertFalse(run.chain().called);
    }

    @Test
    void aSessionThatIsNotSignedInCountsAsSignedOut() throws Exception {
        FakeRequest r = request("GET", "/dashboard/config");
        r.session = new FakeSession(); // a session exists, but no admin flag
        Run run = run(r);
        assertEquals("/app/login", run.response().redirect);
        assertFalse(run.chain().called);
    }

    @Test
    void aSignedInGetPasses() throws Exception {
        FakeRequest r = request("GET", "/dashboard");
        r.session = signedInSession();
        assertTrue(run(r).chain().called);
    }

    @Test
    void aPostWithoutAMatchingTokenIsRefusedBeforeReachingAnything() throws Exception {
        FakeRequest missing = request("POST", "/dashboard/config");
        missing.session = signedInSession();
        Run a = run(missing);
        assertEquals(403, a.response().errorStatus);
        assertFalse(a.chain().called);

        FakeRequest wrong = request("POST", "/dashboard/config");
        wrong.session = signedInSession();
        CsrfTokens.tokenFor(wrong.session.session);
        wrong.parameters.put("csrf", "not-the-token");
        Run b = run(wrong);
        assertEquals(403, b.response().errorStatus);
        assertFalse(b.chain().called);
    }

    @Test
    void aPostWithTheSessionsTokenPasses() throws Exception {
        FakeRequest r = request("POST", "/dashboard/config");
        r.session = signedInSession();
        r.parameters.put("csrf", CsrfTokens.tokenFor(r.session.session));
        assertTrue(run(r).chain().called);
    }

    @Test
    void logoutNeedsSignInAndAToken() throws Exception {
        FakeRequest signedOut = request("POST", "/logout");
        assertEquals("/app/login", run(signedOut).response().redirect);

        FakeRequest noToken = request("POST", "/logout");
        noToken.session = signedInSession();
        Run refused = run(noToken);
        assertEquals(403, refused.response().errorStatus);
        assertFalse(refused.chain().called);

        FakeRequest ok = request("POST", "/logout");
        ok.session = signedInSession();
        ok.parameters.put("csrf", CsrfTokens.tokenFor(ok.session.session));
        assertTrue(run(ok).chain().called);
    }

    @Test
    void tokensAreStablePerSessionAndDifferAcrossSessions() {
        FakeSession one = new FakeSession();
        FakeSession two = new FakeSession();
        String first = CsrfTokens.tokenFor(one.session);
        assertEquals(first, CsrfTokens.tokenFor(one.session));
        assertFalse(first.equals(CsrfTokens.tokenFor(two.session)));
        assertTrue(CsrfTokens.valid(one.session, first));
        assertFalse(CsrfTokens.valid(one.session, null));
        assertFalse(CsrfTokens.valid(two.session, first));
    }
}
