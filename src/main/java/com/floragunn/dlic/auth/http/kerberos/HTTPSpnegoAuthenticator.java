/*
 * Copyright 2016 by floragunn UG (haftungsbeschränkt) - All rights reserved
 * 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed here is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * This software is free of charge for non-commercial and academic use. 
 * For commercial use in a production environment you have to obtain a license 
 * from https://floragunn.com
 * 
 */

package com.floragunn.dlic.auth.http.kerberos;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import javax.xml.bind.DatatypeConverter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;

import com.floragunn.dlic.auth.http.kerberos.util.JaasKrbUtil;
import com.floragunn.dlic.auth.http.kerberos.util.KrbConstants;
import com.floragunn.searchguard.auth.HTTPAuthenticator;
import com.floragunn.searchguard.user.AuthCredentials;
import com.google.common.base.Strings;

public class HTTPSpnegoAuthenticator implements HTTPAuthenticator {

    static {
        printLicenseInfo();
    }
    
    protected final Logger log = LogManager.getLogger(this.getClass());
    
    private final boolean stripRealmFromPrincipalName;
    private String acceptorPrincipal;
    private Path acceptorKeyTabPath;

    public HTTPSpnegoAuthenticator(final Settings settings) {
        super();
        
        if (settings.getAsBoolean("krb_debug", false)) {
            System.out.println("Kerberos debug is enabled");
            log.info("Kerberos debug is enabled on stdout");
            JaasKrbUtil.ENABLE_DEBUG = true;
            System.setProperty("sun.security.krb5.debug", "true");
            System.setProperty("java.security.debug", "all");
            System.setProperty("java.security.auth.debug", "all");
            System.setProperty("sun.security.spnego.debug", "true");
        } else {
            log.debug("Kerberos debug is NOT enabled");
        }
        
        Path configDir = new Environment(settings).configFile();
        
        System.setProperty(KrbConstants.USE_SUBJECT_CREDS_ONLY_PROP, "false");
        
        String krb5Path =  settings.get("searchguard.kerberos.krb5_filepath");
        
        if(!Strings.isNullOrEmpty(krb5Path)) {
            
            if(Paths.get(krb5Path).isAbsolute()) {
                log.debug("krb5_filepath: {}", krb5Path);
                System.setProperty(KrbConstants.KRB5_CONF_PROP, krb5Path);
            } else {
                krb5Path = configDir.resolve(krb5Path).toAbsolutePath().toString();
                log.debug("krb5_filepath (resolved from {}): {}", configDir, krb5Path);
            }
            
            System.setProperty(KrbConstants.KRB5_CONF_PROP, krb5Path);
        } else {
            if(Strings.isNullOrEmpty(System.getProperty(KrbConstants.KRB5_CONF_PROP))) {
                System.setProperty(KrbConstants.KRB5_CONF_PROP, "/etc/krb5.conf");
                log.debug("krb5_filepath (was not set or configured, set to default): /etc/krb5.conf");
            }
        }
        
        stripRealmFromPrincipalName = settings.getAsBoolean("strip_realm_from_principal", true);
        acceptorPrincipal = settings.get("searchguard.kerberos.acceptor_principal");
        String _acceptorKeyTabPath = settings.get("searchguard.kerberos.acceptor_keytab_filepath");
        
        if(acceptorPrincipal == null || acceptorPrincipal.length() == 0) {
            log.error("acceptor_principal must not be null or empty. Kerberos authentication will not work");
            acceptorPrincipal = null;
        } 
        
        if(_acceptorKeyTabPath == null || _acceptorKeyTabPath.length() == 0) {
            log.error("searchguard.kerberos.acceptor_keytab_filepath must not be null or empty. Kerberos authentication will not work");
            acceptorKeyTabPath = null;
        } else {
            acceptorKeyTabPath = configDir.resolve(settings.get("searchguard.kerberos.acceptor_keytab_filepath"));
            
            if(!Files.exists(acceptorKeyTabPath)) {
                log.error("Unable to read keytab from {} - Maybe the file does not exist or is not readable. Kerberos authentication will not work", acceptorKeyTabPath);
                acceptorKeyTabPath = null;
            }
        }
        
        log.debug("strip_realm_from_principal {}", stripRealmFromPrincipalName);
        log.debug("acceptor_principal {}", acceptorPrincipal);
        log.debug("acceptor_keytab_filepath {}", acceptorKeyTabPath);
    }
    
    @Override
    public AuthCredentials extractCredentials(final RestRequest request, ThreadContext threadContext) {
        final SecurityManager sm = System.getSecurityManager();

        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }

        AuthCredentials creds = AccessController.doPrivileged(new PrivilegedAction<AuthCredentials>() {
            @Override
            public AuthCredentials run() {                        
                return extractCredentials0(request);
            }
        });
        
        return creds;
    }

    private AuthCredentials extractCredentials0(final RestRequest request) {
        
        if (acceptorPrincipal == null || acceptorKeyTabPath == null) {
            log.error("Missing acceptor principal or keytab configuration. Kerberos authentication will not work");
            return null;
        }
        
        Principal principal = null;
        final String authorizationHeader = request.header("Authorization");

        if (authorizationHeader != null) {
            if (!authorizationHeader.trim().toLowerCase().startsWith("negotiate ")) {
                log.warn("No 'Negotiate Authorization' header, send 401 and 'WWW-Authenticate Negotiate'");
                return null;
            } else {
                final byte[] decodedNegotiateHeader = DatatypeConverter.parseBase64Binary(authorizationHeader.substring(10));

                GSSContext gssContext = null;
                byte[] outToken = null;

                try {

                    final Subject subject = JaasKrbUtil.loginUsingKeytab(acceptorPrincipal, acceptorKeyTabPath, false);

                    final GSSManager manager = GSSManager.getInstance();
                    final int credentialLifetime = GSSCredential.INDEFINITE_LIFETIME;

                    final PrivilegedExceptionAction<GSSCredential> action = new PrivilegedExceptionAction<GSSCredential>() {
                        @Override
                        public GSSCredential run() throws GSSException {
                            return manager.createCredential(null, credentialLifetime, KrbConstants.SPNEGO, GSSCredential.ACCEPT_ONLY);
                        }
                    };
                    gssContext = manager.createContext(Subject.doAs(subject, action));

                    outToken = Subject.doAs(subject, new AcceptAction(gssContext, decodedNegotiateHeader));

                    if (outToken == null) {
                        log.warn("Ticket validation not successful, outToken is null");
                        return null;
                    }

                    principal = Subject.doAs(subject, new AuthenticateAction(log, gssContext, stripRealmFromPrincipalName));

                } catch (final LoginException e) {
                    log.error("Login exception due to {}", e, e.toString());
                    return null;
                } catch (final GSSException e) {
                    log.error("Ticket validation not successful due to {}", e, e.toString());
                    return null;
                } catch (final PrivilegedActionException e) {
                    final Throwable cause = e.getCause();
                    if (cause instanceof GSSException) {
                        log.warn("Service login not successful due to {}", e, e.toString());
                    } else {
                        log.error("Service login not successful due to {}", e, e.toString());
                    }
                    return null;
                } finally {
                    if (gssContext != null) {
                        try {
                            gssContext.dispose();
                        } catch (final GSSException e) {
                            // Ignore
                        }
                    }
                    //TODO subject logout
                }

                if (principal == null) {
                    return new AuthCredentials("_incomplete_", (Object) outToken);
                }
                

                final String username = ((SimpleUserPrincipal) principal).getName();
                
                if(username == null || username.length() == 0) {
                    log.error("Got empty or null user from kerberos. Normally this means that you acceptor principal {} does not match the server hostname", acceptorPrincipal);
                }
                
                return new AuthCredentials(username, (Object) outToken).markComplete();
                
            }
        } else {
            log.trace("No 'Authorization' header, send 401 and 'WWW-Authenticate Negotiate'");
            return null;
        }
        
    }

    @Override
    public boolean reRequestAuthentication(final RestChannel channel, AuthCredentials creds) {
        final BytesRestResponse wwwAuthenticateResponse = new BytesRestResponse(RestStatus.UNAUTHORIZED, "");
        
        if(creds == null || creds.getNativeCredentials() == null) {
            wwwAuthenticateResponse.addHeader("WWW-Authenticate", "Negotiate");
        } else {
            wwwAuthenticateResponse.addHeader("WWW-Authenticate", "Negotiate "+DatatypeConverter.printBase64Binary((byte[]) creds.getNativeCredentials()));
        }
        channel.sendResponse(wwwAuthenticateResponse);
        return true;
    }

    @Override
    public String getType() {
        return "spnego";
    }
    
    /**
     * This class gets a gss credential via a privileged action.
     */
    //borrowed from Apache Tomcat 8 http://svn.apache.org/repos/asf/tomcat/tc8.0.x/trunk/
    private static class AcceptAction implements PrivilegedExceptionAction<byte[]> {

        GSSContext gssContext;

        byte[] decoded;

        AcceptAction(final GSSContext context, final byte[] decodedToken) {
            this.gssContext = context;
            this.decoded = decodedToken;
        }

        @Override
        public byte[] run() throws GSSException {
            return gssContext.acceptSecContext(decoded, 0, decoded.length);
        }
    }

    //borrowed from Apache Tomcat 8 http://svn.apache.org/repos/asf/tomcat/tc8.0.x/trunk/
    private static class AuthenticateAction implements PrivilegedAction<Principal> {

        private final Logger logger;
        private final GSSContext gssContext;
        private final boolean strip;

        private AuthenticateAction(final Logger logger, final GSSContext gssContext, final boolean strip) {
            super();
            this.logger = logger;
            this.gssContext = gssContext;
            this.strip = strip;
        }

        @Override
        public Principal run() {
            return new SimpleUserPrincipal(getUsernameFromGSSContext(gssContext, strip, logger));
        }
    }

    //borrowed from Apache Tomcat 8 http://svn.apache.org/repos/asf/tomcat/tc8.0.x/trunk/
    private static String getUsernameFromGSSContext(final GSSContext gssContext, final boolean strip, final Logger logger) {
        if (gssContext.isEstablished()) {
            GSSName gssName = null;
            try {
                gssName = gssContext.getSrcName();
            } catch (final GSSException e) {
                logger.error("Unable to get src name from gss context", e);
            }

            if (gssName != null) {
                String name = gssName.toString();
                return stripRealmName(name, strip);
            } else {
                logger.error("GSS name is null");
            }
        } else {
            logger.error("GSS context not established");
        }

        return null;
    }
    
    private static String stripRealmName(String name, boolean strip){
        if (strip && name != null) {
            final int i = name.indexOf('@');
            if (i > 0) {
                // Zero so we don;t leave a zero length name
                name = name.substring(0, i);
            }
        }
        
        return name;
    }

    private static class SimpleUserPrincipal implements Principal, Serializable {

        private static final long serialVersionUID = -1;
        private final String username;

        SimpleUserPrincipal(final String username) {
            super();
            this.username = username;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((username == null) ? 0 : username.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final SimpleUserPrincipal other = (SimpleUserPrincipal) obj;
            if (username == null) {
                if (other.username != null) {
                    return false;
                }
            } else if (!username.equals(other.username)) {
                return false;
            }
            return true;
        }

        @Override
        public String getName() {
            return this.username;
        }

        @Override
        public String toString() {
            final StringBuilder buffer = new StringBuilder();
            buffer.append("[principal: ");
            buffer.append(this.username);
            buffer.append("]");
            return buffer.toString();
        }
    }
    
    public static void printLicenseInfo() {
        System.out.println("***********************************************");
        System.out.println("Searchguard Kerberos/SPNEGO is not free software");
        System.out.println("for commercial use in production.");
        System.out.println("You have to obtain a license if you ");
        System.out.println("use it in production.");
        System.out.println("***********************************************");
    }
}