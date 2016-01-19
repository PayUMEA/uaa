package org.cloudfoundry.identity.uaa.login;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeStore;
import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.validate.PasswordValidator;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.UaaStringUtils;
import org.cloudfoundry.identity.uaa.util.UaaUrlUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class EmailAccountCreationService implements AccountCreationService {

    private final Log logger = LogFactory.getLog(getClass());

    public static final String SIGNUP_REDIRECT_URL = "signup_redirect_url";

    private final SpringTemplateEngine templateEngine;
    private final MessageService messageService;
    private final ExpiringCodeStore codeStore;
    private final ScimUserProvisioning scimUserProvisioning;
    private final ClientDetailsService clientDetailsService;
    private final PasswordValidator passwordValidator;
    private final String brand;
    private final UaaUrlUtils uaaUrlUtils;

    public EmailAccountCreationService(
            SpringTemplateEngine templateEngine,
            MessageService messageService,
            ExpiringCodeStore codeStore,
            ScimUserProvisioning scimUserProvisioning,
            ClientDetailsService clientDetailsService,
            PasswordValidator passwordValidator,
            UaaUrlUtils uaaUrlUtils,
            String brand) {

        this.templateEngine = templateEngine;
        this.messageService = messageService;
        this.codeStore= codeStore;
        this.scimUserProvisioning = scimUserProvisioning;
        this.clientDetailsService = clientDetailsService;
        this.passwordValidator = passwordValidator;
        this.uaaUrlUtils = uaaUrlUtils;
        this.brand = brand;
    }

    @Override
    public void beginActivation(String email, String password, String clientId, String redirectUri) {
        passwordValidator.validate(password);

        String subject = getSubjectText();
        try {
            ScimUser scimUser = createUser(email, password, Origin.UAA);
            generateAndSendCode(email, clientId, subject, scimUser.getId(), redirectUri);
        } catch (ScimResourceAlreadyExistsException e) {
            List<ScimUser> users = scimUserProvisioning.query("userName eq \""+email+"\" and origin eq \""+Origin.UAA+"\"");
            try {
                if (users.size()>0) {
                    if (users.get(0).isVerified()) {
                        throw new UaaException("User already active.", HttpStatus.CONFLICT.value());
                    } else {
                        generateAndSendCode(email, clientId, subject, users.get(0).getId(), redirectUri);
                    }
                }

            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } catch (IOException e) {
            logger.error("Exception raised while creating account activation email for " + email, e);
        }
    }

    private void generateAndSendCode(String email, String clientId, String subject, String userId, String redirectUri) throws IOException {
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + (60 * 60 * 1000)); // 1 hour
        ExpiringCode expiringCodeForPost = getExpiringCode(userId, clientId, expiresAt, redirectUri);
        ExpiringCode expiringCode = codeStore.generateCode(expiringCodeForPost.getData(), expiringCodeForPost.getExpiresAt());
        String htmlContent = getEmailHtml(expiringCode.getCode(), email);

        messageService.sendMessage(email, MessageType.CREATE_ACCOUNT_CONFIRMATION, subject, htmlContent);
    }

    private ExpiringCode getExpiringCode(String userId, String clientId, Timestamp expiresAt, String redirectUri) throws IOException {
        Map<String, String> codeData = new HashMap<>();
        codeData.put("user_id", userId);
        codeData.put("client_id", clientId);
        codeData.put("redirect_uri", redirectUri);
        String codeDataString = JsonUtils.writeValueAsString(codeData);
        return new ExpiringCode(null, expiresAt, codeDataString);
    }

    @Override
    public AccountCreationResponse completeActivation(String code) throws IOException {

        ExpiringCode expiringCode = codeStore.retrieveCode(code);
        if (expiringCode==null) {
            //just to satisfy unit tests
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        }

        Map<String, String> data = JsonUtils.readValue(expiringCode.getData(), new TypeReference<Map<String, String>>() {});
        ScimUser user = scimUserProvisioning.retrieve(data.get("user_id"));
        user = scimUserProvisioning.verifyUser(user.getId(), user.getVersion());

        String clientId = data.get("client_id");
        String redirectUri = data.get("redirect_uri") != null ? data.get("redirect_uri") : "";
        String redirectLocation = getDefaultRedirect();
        if (clientId != null) {
            try {
                ClientDetails clientDetails = clientDetailsService.loadClientByClientId(clientId);
                Set<String> redirectUris = clientDetails.getRegisteredRedirectUri() == null ? Collections.emptySet() :
                        clientDetails.getRegisteredRedirectUri();
                Set<Pattern> wildcards = UaaStringUtils.constructWildcards(redirectUris);
                if (UaaStringUtils.matches(wildcards, redirectUri)) {
                    redirectLocation = redirectUri;
                } else {
                    redirectLocation = (String) clientDetails.getAdditionalInformation().get(SIGNUP_REDIRECT_URL);
                }
            } catch (NoSuchClientException e) {
            }
        }
        return new AccountCreationResponse(user.getId(), user.getUserName(), user.getUserName(), redirectLocation);
    }

    private String getDefaultRedirect() throws IOException {
        return "home";
    }

    @Override
    public void resendVerificationCode(String email, String clientId) {
        List<ScimUser> resources = scimUserProvisioning.query("userName eq \"" + email + "\" and origin eq \"" + Origin.UAA + "\"");
        ExpiringCode previousCode = codeStore.retrieveLatest(email, clientId);
        String redirect_uri = "";
        if (previousCode != null) {
            redirect_uri = (String) JsonUtils.readValue(previousCode.getData(), Map.class).get("redirect_uri");
        }
        String userId = resources.get(0).getId();
        try {
            generateAndSendCode(email, clientId, getSubjectText(), userId, redirect_uri);
        } catch (IOException e) {
            logger.error("Exception raised while resending activation email for " + email, e);
        }
    }

    @Override
    public ScimUser createUser(String username, String password, String origin) {
        if (Origin.UNKNOWN.equals(origin)) {
            List<ScimUser> results = scimUserProvisioning.query(String.format("username eq \"%s\" and origin eq \"%s\"", username, Origin.UNKNOWN));
            if (results!=null && results.size()==1) {
                return results.get(0);
            }
        }
        ScimUser scimUser = new ScimUser();
        scimUser.setUserName(username);
        ScimUser.Email email = new ScimUser.Email();
        email.setPrimary(true);
        email.setValue(username);
        scimUser.setEmails(Arrays.asList(email));
        scimUser.setOrigin(origin);
        scimUser.setPassword(password);
        try {
            ScimUser userResponse = scimUserProvisioning.createUser(scimUser, password);
            return userResponse;
        } catch (RuntimeException x) {
            if (x instanceof ScimResourceAlreadyExistsException) {
                throw x;
            }
            throw new UaaException("Couldn't create user:"+username, x);
        }
    }

    private String getSubjectText() {
        return brand.equals("pivotal") && IdentityZoneHolder.isUaa() ? "Activate your Pivotal ID" : "Activate your account";
    }

    private String getEmailHtml(String code, String email) {
        String accountsUrl = uaaUrlUtils.getUaaUrl("/verify_user");

        final Context ctx = new Context();
        if (IdentityZoneHolder.isUaa()) {
            ctx.setVariable("serviceName", brand.equals("pivotal") ? "Pivotal" : "Cloud Foundry");
        } else {
            ctx.setVariable("serviceName", IdentityZoneHolder.get().getName());
        }
        ctx.setVariable("servicePhrase", brand.equals("pivotal") && IdentityZoneHolder.isUaa() ? "a Pivotal ID" : "an account");
        ctx.setVariable("code", code);
        ctx.setVariable("email", email);
        ctx.setVariable("accountsUrl", accountsUrl);
        return templateEngine.process("activate", ctx);
    }
}
