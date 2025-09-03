package org.keycloak.marjaa.providers.login.recaptcha.authenticator;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.events.Details;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.util.JsonSerialization;
import org.keycloak.models.utils.FormMessage;

import  jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.*;

public class RecaptchaUsernamePasswordForm extends UsernamePasswordForm implements Authenticator{
	public static final String G_RECAPTCHA_RESPONSE = "g-recaptcha-response";
	public static final String SITE_KEY = "site.key";
	public static final String SITE_SECRET = "secret";
	public static final String USE_RECAPTCHA_NET = "useRecaptchaNet";
	public static final String ACTION = "action";
	public static final String INVISIBLE = "recaptcha.v3";
	private static final Logger logger = Logger.getLogger(RecaptchaUsernamePasswordForm.class);

	private String siteKey;
	private String userLanguageTag;
	private String action;
	private boolean invisible;
	private AuthenticatorConfigModel captchaConfig;

	@Override
	protected Response createLoginForm( LoginFormsProvider form ) {
		applyCaptcha(null, form);
		return super.createLoginForm( form );
	}

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");
		if (logger.isInfoEnabled()) {
			logger.info("validateRecaptcha(AuthenticationFlowContext, boolean, String, String) - Before the validation");
		}
		loadConfig(context);

		AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
		LoginFormsProvider form = context.form();

		if (captchaConfig == null || captchaConfig.getConfig() == null
				|| captchaConfig.getConfig().get(SITE_KEY) == null
				|| captchaConfig.getConfig().get(SITE_SECRET) == null) {
			form.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED));
			return;
		}

		applyCaptcha(context, form);
		super.authenticate(context);
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		if (logger.isDebugEnabled()) {
			logger.debug("action(AuthenticationFlowContext) - start");
		}
		logger.debug(context.getHttpRequest());
		loadConfig(context);

		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");

		AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
		String secret = captchaConfig.getConfig().get(SITE_SECRET);
		String captcha = formData.getFirst(G_RECAPTCHA_RESPONSE);

		boolean success = !Validation.isBlank(captcha) && validateRecaptcha(context, false, captcha, secret);

		if (!success) {
			context.getEvent().error(Messages.RECAPTCHA_FAILED);
			// Response challengeResponse = context.form().setError(Messages.RECAPTCHA_FAILED).createLoginUsernamePassword();
			Response challengeResponse = applyCaptcha(context).setError(Messages.RECAPTCHA_FAILED).createLoginUsernamePassword();
			context.forceChallenge(challengeResponse);
			return;
		}

		applyCaptcha(context);

		if (!super.validateForm(context, formData)) {
			return;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("action(AuthenticationFlowContext) - success");
		}

		context.success();
	}

	private void loadConfig(AuthenticationFlowContext context){
		if (context == null){
			return;
		}
		userLanguageTag = context.getSession().getContext().resolveLocale(context.getUser()).toLanguageTag();
		captchaConfig = context.getAuthenticatorConfig();
	}

	private LoginFormsProvider applyCaptcha(AuthenticationFlowContext context) {
		LoginFormsProvider form = context.form();
		return applyCaptcha(context, form);
	}

	private LoginFormsProvider applyCaptcha(LoginFormsProvider form) {
		return applyCaptcha(null, form);
	}

	private String getRecaptchaDomain(AuthenticatorConfigModel config) {
		Boolean useRecaptcha = Optional.ofNullable(config)
				.map(configModel -> configModel.getConfig())
				.map(cfg -> Boolean.valueOf(cfg.get(USE_RECAPTCHA_NET)))
				.orElse(false);
		if (useRecaptcha) {
			return "recaptcha.net";
		}

		return "google.com";
	}

	protected String getScriptUrl(AuthenticatorConfigModel config, String userLanguageTag) {
			return "https://www." + getRecaptchaDomain(config) + "/recaptcha/api.js?hl=" + userLanguageTag;
	}

	private LoginFormsProvider applyCaptcha(AuthenticationFlowContext context, LoginFormsProvider form) {
		loadConfig(context);
		
		siteKey = captchaConfig.getConfig().get(SITE_KEY);
		action = captchaConfig.getConfig().get(ACTION);
		invisible = Boolean.valueOf(captchaConfig.getConfig().get(INVISIBLE));

		form.setAttribute("recaptchaRequired", true);
		form.setAttribute("recaptchaSiteKey", siteKey);
		form.setAttribute("recaptchaAction", action);
		form.setAttribute("recaptchaVisible", !invisible);
		form.addScript(getScriptUrl(captchaConfig, userLanguageTag)); 

		return form;
	}

	protected boolean validateRecaptcha(AuthenticationFlowContext context, boolean success, String captcha, String secret) {
		HttpClient httpClient = context.getSession().getProvider(HttpClientProvider.class).getHttpClient();
		HttpPost post = new HttpPost("https://www." + getRecaptchaDomain(context.getAuthenticatorConfig()) + "/recaptcha/api/siteverify");
		List<NameValuePair> formparams = new LinkedList<>();
		formparams.add(new BasicNameValuePair("secret", secret));
		formparams.add(new BasicNameValuePair("response", captcha));
		formparams.add(new BasicNameValuePair("remoteip", context.getConnection().getRemoteAddr()));
		try {
			UrlEncodedFormEntity form = new UrlEncodedFormEntity(formparams, "UTF-8");
			post.setEntity(form);
			HttpResponse response = httpClient.execute(post);
			InputStream content = response.getEntity().getContent();
			try {
				Map json = JsonSerialization.readValue(content, Map.class);
				Object val = json.get("success");
				success = Boolean.TRUE.equals(val);
			} finally {
				content.close();
			}
		} catch (Exception e) {
			ServicesLogger.LOGGER.recaptchaFailed(e);
		}
		return success;
	}

}
