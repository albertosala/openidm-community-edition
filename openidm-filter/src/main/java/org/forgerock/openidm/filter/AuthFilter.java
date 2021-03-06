/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011-2013 ForgeRock AS. All rights reserved.
 */

package org.forgerock.openidm.filter;

// Java Standard Edition
import java.io.IOException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.binary.Base64;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.JsonResource;
import org.forgerock.json.resource.JsonResourceException;
import org.forgerock.openidm.audit.util.Status;
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.http.ContextRegistrator;
import org.forgerock.openidm.objset.BadRequestException;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.JsonResourceObjectSet;
import org.forgerock.openidm.objset.ObjectSet;
import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.util.DateUtil;
import org.ops4j.pax.web.extender.whiteboard.FilterMapping;
import org.ops4j.pax.web.extender.whiteboard.runtime.DefaultFilterMapping;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auth Filter
 * @author Jamie Nelson
 * @author aegloff
 * @author ckienle
 */

@Component(
    name = "org.forgerock.openidm.authentication", immediate = true,
    policy = ConfigurationPolicy.REQUIRE
)
@Service(value = {AuthFilterService.class, JsonResource.class})
@Properties({
        @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
        @Property(name = Constants.SERVICE_DESCRIPTION, value = "OpenIDM Authentication Filter Service"),
        @Property(name = "openidm.router.prefix", value = "authentication")
})
public class AuthFilter 
        implements Filter, AuthFilterService, JsonResource {

    private final static Logger logger = LoggerFactory.getLogger(AuthFilter.class);

    /** Attribute in session containing authenticated username. */
    public static final String USERNAME_ATTRIBUTE = "openidm.username";
    
    /** Attribute in session containing authenticated userid. */
    public static final String USERID_ATTRIBUTE = "openidm.userid";

    /** Attribute in session and request containing assigned roles. */
    public static final String ROLES_ATTRIBUTE = "openidm.roles";

    /** Attribute in session containing user's resource (managed_user or internal_user) */
    public static final String RESOURCE_ATTRIBUTE = "openidm.resource";
    
    /** Attribute in request to indicate to openidm down stream that an authentication filter has secured the request */
    public static final String OPENIDM_AUTHINVOKED = "openidm.authinvoked";

    /** Authentication username header */
    public static final String HEADER_USERNAME = "X-OpenIDM-Username";

    /** Authentication password header */
    public static final String HEADER_PASSWORD = "X-OpenIDM-Password";

    /** Re-authentication password header */
    public static final String HEADER_REAUTH_PASSWORD = "X-OpenIDM-Reauth-Password";

    /** The authentication module to delegate to */
    private AuthModule authModule;
    
    // name of the header containing the client IPAddress, used for the audit record
    // typically X-Forwarded-For
    static String logClientIPHeader = null;
    private static DateUtil dateUtil;

    public enum Action {
        authenticate, logout
    }

    static class AuthData {
       String username;
       String userId;
       List<String> roles = new ArrayList<String>();
       boolean status = false;
       String resource = "default";
    };

    // A list of ports that allow authentication purely based on client certificates (SSL mutual auth)
    static Set<Integer> clientAuthOnly = new HashSet<Integer>();

    private FilterConfig config = null;
    public void init(FilterConfig config) throws ServletException {
          this.config = config;

          String clientAuthOnlyStr = System.getProperty("openidm.auth.clientauthonlyports");
          if (clientAuthOnlyStr != null) {
              String[] split = clientAuthOnlyStr.split(",");
              for (String entry : split) {
                  clientAuthOnly.add(Integer.valueOf(entry));
              }
          }
          logger.info("Authentication disabled on ports: {}", clientAuthOnly);
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                                                        throws IOException, ServletException {
        if (router == null) {
            throw new ServletException("Internal services not ready to process requests.");
        }

        HttpServletRequest req = (HttpServletRequest)request;
        HttpServletResponse res = (HttpServletResponse)response;
        AuthData authData = new AuthData();

        try {
            HttpSession session = req.getSession(false);
            String logout = req.getHeader("X-OpenIDM-Logout");
            String headerLogin = req.getHeader(HEADER_USERNAME);
            String basicAuth = req.getHeader("Authorization");
            if (logout != null) {
                if (session != null) {
                    session.invalidate();
                }
                res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
              // if we see the certficate port this request is for client auth only
            } else if (allowClientCertOnly(req)) {
                authData = hasClientCert(req);
                logAuth(req, authData.username, authData.userId, authData.roles, Action.authenticate, Status.SUCCESS);
            } else if (session == null && headerLogin != null) {
                authData = authenticateUser(req);
                logAuth(req, authData.username, authData.userId, authData.roles, Action.authenticate, Status.SUCCESS);
                createSession(req, session, authData);
            } else if (session == null && basicAuth != null) {
                authData = doBasicAuth(basicAuth);
                logAuth(req, authData.username, authData.userId, authData.roles, Action.authenticate, Status.SUCCESS);
                createSession(req, session, authData);
            } else if (session != null) {
                authData.username = (String)session.getAttribute(USERNAME_ATTRIBUTE);
                authData.userId = (String)session.getAttribute(USERID_ATTRIBUTE);
                authData.roles = (List<String>) session.getAttribute(ROLES_ATTRIBUTE);
                authData.resource = (String)session.getAttribute(RESOURCE_ATTRIBUTE);
            } else {
                authFailed(req, res, authData.username);
                return;
            }
        } catch (AuthException s) {
            authFailed(req, res, s.getMessage());
            return;
        }
        logger.debug("Found valid session for {} id {} with roles {}", new Object[] {authData.username, authData.userId, authData.roles});
        req.setAttribute(USERID_ATTRIBUTE, authData.userId);
        req.setAttribute(ROLES_ATTRIBUTE, authData.roles);
        req.setAttribute(RESOURCE_ATTRIBUTE, authData.resource);
        req.setAttribute(OPENIDM_AUTHINVOKED, "openidmfilter");
 
        chain.doFilter(new UserWrapper(req, authData.username, authData.userId, authData.roles), res);
    }

    private void authFailed(HttpServletRequest req, HttpServletResponse res, String username) throws IOException {
        logAuth(req, username, null, null, Action.authenticate, Status.FAILURE);
        JsonResourceException jre = new JsonResourceException(401, "Access denied");
        res.getWriter().write(jre.toJsonValue().toString());
        res.setContentType("application/json");
        res.setStatus(401);
    }

    private static void logAuth(HttpServletRequest req, String username, String userId,
                            List<String> roles, Action action, Status status) {
        try {
            Map<String,Object> entry = new HashMap<String,Object>();
            entry.put("timestamp", dateUtil.now());
            entry.put("action", action.toString());
            entry.put("status", status.toString());
            entry.put("principal", username);
            entry.put("userid", userId);
            entry.put("roles", roles);
            // check for header sent by load balancer for IPAddr of the client
            String ipAddress;
            if (logClientIPHeader == null ) {
                ipAddress = req.getRemoteAddr();
            } else {
                ipAddress = req.getHeader(logClientIPHeader);
                if (ipAddress == null) {
                    ipAddress = req.getRemoteAddr();
                }
            }
            entry.put("ip", ipAddress);
            if (router != null) {
                router.create("audit/access", entry);
            } else {
                // Filter should have rejected request if router is not available
                logger.warn("Failed to log entry for {} as router is null.", username);
            }
        } catch (ObjectSetException ose) {
            logger.warn("Failed to log entry for {}", username, ose);
        }
    }

    private AuthData doBasicAuth(String data) throws AuthException {

        logger.debug("HTTP basic authentication request");
        AuthData ad = new AuthData();
        StringTokenizer st = new StringTokenizer(data);
        String isBasic = st.nextToken();
        if (isBasic == null || !isBasic.equalsIgnoreCase("Basic")) {
            throw new AuthException("");
        }
        String creds= st.nextToken();
        if (creds == null) {
            throw new AuthException("");
        }
        String dcreds = new String(Base64.decodeBase64(creds.getBytes()));
        String[] t = dcreds.split(":");
        if (t.length != 2) {
            throw new AuthException("");
        }
        ad.username = t[0];
        authModule.authenticate(ad, t[1]);
        if (ad.status == false) {
            throw new AuthException(ad.username);
        }
        return ad;
    }

    private void createSession(HttpServletRequest req, HttpSession sess, AuthData ad) {
        if (req.getHeader("X-OpenIDM-NoSession") == null) {
            sess = req.getSession();
            sess.setAttribute(USERNAME_ATTRIBUTE, ad.username);
            sess.setAttribute(USERID_ATTRIBUTE, ad.userId);
            sess.setAttribute(ROLES_ATTRIBUTE, ad.roles);
            sess.setAttribute(RESOURCE_ATTRIBUTE, ad.resource);
            sess.setMaxInactiveInterval(authModule.sessionTimeout);

            if (logger.isDebugEnabled()) {
                logger.debug("Created session for: {} with id {}, roles {} and resource: {}",
                    new Object[] {ad.username, ad.userId, ad.roles, ad.resource});
            }
        }
    }

    /**
     * @param request the full request
     * @return the security context of the request, or null if does not exist
     */
    private JsonValue getSecurityContext(JsonValue request) {
        JsonValue result = new JsonValue(null);
        while (request != null && !request.isNull()) {
            if ("http".equals(request.get("type").asString())) {
                if (!request.get("security").isNull()) {
                    result = request;
                    break;
                }
            }
            request = request.get("parent");
        }
        return result;
    }

    /**
     * Re-authenticate based on the context associated with the request
     * @param request the full request
     * @return authenticated user if success
     * @throws AuthException if reauthentication failed
     */
    public AuthData reauthenticate(JsonValue request) throws AuthException {
        JsonValue secCtx = getSecurityContext(request);
        JsonValue headers = secCtx.get("headers");
        String reauthPassword = null;
        for (Entry<String, Object> entry : headers.asMap().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(HEADER_REAUTH_PASSWORD)) {
                reauthPassword = (String)entry.getValue();
                break;
            }
        }
        AuthData ad = new AuthData();
        ad.username = secCtx.get("security").get("username").asString();
        if (ad.username == null || reauthPassword == null || ad.username.equals("") || reauthPassword.equals("")) {
            logger.debug("Failed authentication, missing or empty headers");
            throw new AuthException("Failed authentication, missing or empty headers");
        }
        ad = authModule.authenticate(ad, reauthPassword);
        if (ad.status == false) {
            throw new AuthException(ad.username);
        }
        return ad;
    }
    
    private AuthData authenticateUser(HttpServletRequest req) throws AuthException {

        logger.debug("No session, authenticating user");
        AuthData ad = new AuthData();
        String password = req.getHeader(HEADER_PASSWORD);
        ad.username = req.getHeader(HEADER_USERNAME);
        if (ad.username == null || password == null || ad.username.equals("") || password.equals("")) {
            logger.debug("Failed authentication, missing or empty headers");
            throw new AuthException();
        }
        ad = authModule.authenticate(ad, password);
        if (ad.status == false) {
            throw new AuthException(ad.username);
        }
        return ad;
    }

    // This is currently Jetty specific
    private AuthData hasClientCert(ServletRequest request) throws AuthException {

        logger.debug("Client certificate authentication request");
        AuthData ad = new AuthData();
        X509Certificate[] certs = getClientCerts(request);

        if (certs != null) {
            Principal existingPrincipal = request instanceof HttpServletRequest ? ((HttpServletRequest)request).getUserPrincipal() : null;
            logger.debug("Request {} existing Principal {} has {} certificates", new Object[] {request, existingPrincipal, certs.length});
            for (X509Certificate cert : certs) {
                logger.debug("Request {} client certificate subject DN: {}", request, cert.getSubjectDN());
            }
        }
        ad.status = (certs != null && certs.length > 0 && certs[0] != null);
        if (ad.status == false) {
            throw new AuthException(ad.username);
        }
        ad.username = certs[0].getSubjectDN().getName();
        ad.userId = ad.username;
        ad.roles.add("openidm-cert");
        logger.debug("Authentication client certificate subject {}", ad.username );
        return ad;
    }

    // This is currently Jetty specific
    private X509Certificate[] getClientCerts(ServletRequest request) {

        Object checkCerts = request.getAttribute("javax.servlet.request.X509Certificate");
        if (checkCerts instanceof X509Certificate[]) {
            return (X509Certificate[]) checkCerts;
        } else {
            logger.warn("Unknown certificate type retrieved {}", checkCerts);
            return null;
        }
    }

    /**
     * Whether to allow authentication purely based on client certificates
     * Note that the checking of the certificates MUST be done by setting
     * jetty up for client auth required.
     * @return true if authentication via client certificate only is sufficient
     */
    private boolean allowClientCertOnly(ServletRequest request) {
        return clientAuthOnly.contains(Integer.valueOf(request.getLocalPort()));
    }

    public void destroy() {
        config = null;
    }

    @Reference(
        name = "ref_Auth_JsonResourceRouterService",
        referenceInterface = JsonResource.class,
        bind = "bindRouter",
        unbind = "unbindRouter",
        cardinality = ReferenceCardinality.MANDATORY_UNARY,
        policy = ReferencePolicy.STATIC,
        target = "(service.pid=org.forgerock.openidm.router)"
    )
    private static ObjectSet router;

    private void bindRouter(JsonResource router) {
        this.router = new JsonResourceObjectSet(router);
    }
    private void unbindRouter(JsonResource router) {
        this.router = null;
    }

    /** TODO: Description. */
    private ComponentContext context;

    /** TODO: Description. */
    private ServiceRegistration serviceRegistration;

    @Activate
    protected synchronized void activate(ComponentContext context) throws ServletException, NamespaceException {
        this.context = context;
        logger.info("Activating Auth Filter with configuration {}", context.getProperties());
        setConfig(context);
        // TODO make this configurable
        dateUtil = DateUtil.getDateUtil("UTC");

        /*String urlPatterns[] = {"/openidm/*"};
        String servletNames[] = null;

        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(ExtenderConstants.PROPERTY_URL_PATTERNS, urlPatterns);
        props.put(ExtenderConstants.PROPERTY_SERVLET_NAMES, servletNames);
        props.put(ExtenderConstants.PROPERTY_HTTP_CONTEXT_ID, "openidm");
        serviceRegistration = context.getBundleContext().registerService(Filter.class.getName(), this, props);*/

        DefaultFilterMapping filterMapping = new DefaultFilterMapping();
        filterMapping.setFilter(this);
        filterMapping.setHttpContextId("openidm");
        filterMapping.setServletNames("OpenIDM REST");//, "OpenIDM Web");
        filterMapping.setUrlPatterns("/openidm/*");//, "/openidmui/*");
        //filterMapping.setInitParams(null);
        serviceRegistration = FrameworkUtil.getBundle(ContextRegistrator.class).getBundleContext().registerService(FilterMapping.class.getName(), filterMapping, null);
    }

    @Modified
    void modified(ComponentContext context) throws Exception {
        logger.info("Modified auth Filter with configuration {}", context.getProperties());
        setConfig(context);
    }

    private void setConfig(ComponentContext context) {
        JsonValue config = new JsonValue(new JSONEnhancedConfig().getConfiguration(context));
        logClientIPHeader = (String) config.get("clientIPHeader").asString();
        authModule = new AuthModule(config);
    }

    @Deactivate
    protected synchronized void deactivate(ComponentContext context) {
        if (serviceRegistration != null) {
            try {
                serviceRegistration.unregister();
                logger.info("Unregistered authentication filter.");
            } catch (Exception ex) {
                logger.warn("Failure reported during unregistering of authentication filter: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * Action support, including reauthenticate action
     * {@inheritDoc}
     */
    public JsonValue handle(JsonValue request) throws JsonResourceException {
        JsonValue response = new JsonValue(new HashMap());
        String id = request.get("id").asString();
        
        JsonValue params = request.get("params");
        String action = params.get("_action").asString();
        if (action == null) {
            throw new BadRequestException("Action parameter is not present or value is null");                
        }
        
        if (id == null) {
            // operation on collection
            if ("reauthenticate".equalsIgnoreCase(action)) {
                try {
                    AuthData reauthenticated = reauthenticate(request);
                    response.put("reauthenticated", Boolean.TRUE);
                    response.put("username", reauthenticated.username);
                } catch (AuthException ex) {
                    throw new ForbiddenException("Reauthentication failed", ex);
                }
            } else {
                throw new BadRequestException("Action " + action + " on authentication service not supported " + params);
            }
        } else {
            throw new BadRequestException("Actions not supported on child resource of authentication service " + params);
        } 
        return response;
    }
}

