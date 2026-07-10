package org.keycloak.marjaa.providers.login.recaptcha.authenticator;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
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

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Username/password authenticator that additionally validates a Google reCAPTCHA
 * response (v2 checkbox or v3 invisible) before delegating to the standard form.
 *
 * NOTE: a single instance is shared across all requests (see the factory SINGLETON),
 * so this class MUST remain stateless. All per-request state is read from the
 * AuthenticationFlowContext / form; never store it in instance fields.
 */
public class RecaptchaUsernamePasswordForm extends UsernamePasswordForm implements Authenticator {
	public static final String G_RECAPTCHA_RESPONSE = "g-recaptcha-response";
	public static final String SITE_KEY = "site.key";
	public static final String SITE_SECRET = "secret";
	public static final String USE_RECAPTCHA_NET = "useRecaptchaNet";
	public static final String ACTION = "action";
	public static final String INVISIBLE = "recaptcha.v3";
	public static final String SCORE_THRESHOLD = "score.threshold";
	public static final String SIZE = "size";

	public static final double DEFAULT_SCORE_THRESHOLD = 0.5d;
	public static final String DEFAULT_SIZE = "normal";

	private static final Logger logger = Logger.getLogger(RecaptchaUsernamePasswordForm.class);

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");

		AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
		LoginFormsProvider form = context.form();

		if (notConfigured(captchaConfig)) {
			form.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED));
			return;
		}

		applyCaptcha(context, form);
		super.authenticate(context);
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");

		AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
		if (notConfigured(captchaConfig)) {
			Response challengeResponse = applyCaptcha(context, context.form())
					.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED))
					.createLoginUsernamePassword();
			context.forceChallenge(challengeResponse);
			return;
		}

		Map<String, String> config = captchaConfig.getConfig();
		String secret = config.get(SITE_SECRET);
		boolean invisible = Boolean.parseBoolean(config.get(INVISIBLE));
		String expectedAction = config.get(ACTION);
		double scoreThreshold = parseScoreThreshold(config.get(SCORE_THRESHOLD));
		String captcha = formData.getFirst(G_RECAPTCHA_RESPONSE);

		boolean success = !Validation.isBlank(captcha)
				&& validateRecaptcha(context, captcha, secret, invisible, expectedAction, scoreThreshold);

		if (!success) {
			context.getEvent().error(Messages.RECAPTCHA_FAILED);
			Response challengeResponse = applyCaptcha(context, context.form())
					.setError(Messages.RECAPTCHA_FAILED)
					.createLoginUsernamePassword();
			context.forceChallenge(challengeResponse);
			return;
		}

		if (!super.validateForm(context, formData)) {
			return;
		}

		context.success();
	}

	private boolean notConfigured(AuthenticatorConfigModel captchaConfig) {
		return captchaConfig == null
				|| captchaConfig.getConfig() == null
				|| captchaConfig.getConfig().get(SITE_KEY) == null
				|| captchaConfig.getConfig().get(SITE_SECRET) == null;
	}

	private double parseScoreThreshold(String raw) {
		if (Validation.isBlank(raw)) {
			return DEFAULT_SCORE_THRESHOLD;
		}
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			logger.warnf("Invalid reCAPTCHA score.threshold '%s', falling back to %s", raw, DEFAULT_SCORE_THRESHOLD);
			return DEFAULT_SCORE_THRESHOLD;
		}
	}

	private String getRecaptchaDomain(AuthenticatorConfigModel config) {
		boolean useRecaptchaNet = Optional.ofNullable(config)
				.map(AuthenticatorConfigModel::getConfig)
				.map(cfg -> Boolean.parseBoolean(cfg.get(USE_RECAPTCHA_NET)))
				.orElse(false);
		return useRecaptchaNet ? "recaptcha.net" : "google.com";
	}

	protected String getScriptUrl(AuthenticatorConfigModel config, String userLanguageTag, boolean invisible, String siteKey) {
		String base = "https://www." + getRecaptchaDomain(config) + "/recaptcha/api.js";
		// v3 requires the site key up front to render/execute; v2 just needs the language hint.
		return invisible ? base + "?render=" + siteKey : base + "?hl=" + userLanguageTag;
	}

	private LoginFormsProvider applyCaptcha(AuthenticationFlowContext context, LoginFormsProvider form) {
		AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
		Map<String, String> config = captchaConfig.getConfig();

		String siteKey = config.get(SITE_KEY);
		String action = config.get(ACTION);
		boolean invisible = Boolean.parseBoolean(config.get(INVISIBLE));
		String size = Validation.isBlank(config.get(SIZE)) ? DEFAULT_SIZE : config.get(SIZE);
		String userLanguageTag = context.getSession().getContext().resolveLocale(context.getUser()).toLanguageTag();

		form.setAttribute("recaptchaRequired", true);
		form.setAttribute("recaptchaSiteKey", siteKey);
		form.setAttribute("recaptchaAction", action);
		form.setAttribute("recaptchaVisible", !invisible);
		form.setAttribute("recaptchaSize", size);
		form.addScript(getScriptUrl(captchaConfig, userLanguageTag, invisible, siteKey));

		return form;
	}

	protected boolean validateRecaptcha(AuthenticationFlowContext context, String captcha, String secret,
			boolean invisible, String expectedAction, double scoreThreshold) {
		HttpClient httpClient = context.getSession().getProvider(HttpClientProvider.class).getHttpClient();
		HttpPost post = new HttpPost("https://www." + getRecaptchaDomain(context.getAuthenticatorConfig()) + "/recaptcha/api/siteverify");
		List<NameValuePair> formparams = new LinkedList<>();
		formparams.add(new BasicNameValuePair("secret", secret));
		formparams.add(new BasicNameValuePair("response", captcha));
		formparams.add(new BasicNameValuePair("remoteip", context.getConnection().getRemoteAddr()));
		try {
			post.setEntity(new UrlEncodedFormEntity(formparams, "UTF-8"));
			HttpResponse response = httpClient.execute(post);
			try (InputStream content = response.getEntity().getContent()) {
				@SuppressWarnings("unchecked")
				Map<String, Object> json = JsonSerialization.readValue(content, Map.class);

				if (!Boolean.TRUE.equals(json.get("success"))) {
					logger.debugf("reCAPTCHA verification unsuccessful: %s", json.get("error-codes"));
					return false;
				}

				// v2 (checkbox) is a pass/fail; v3 returns a risk score and the action name.
				if (invisible) {
					double score = json.get("score") instanceof Number ? ((Number) json.get("score")).doubleValue() : 0.0d;
					if (score < scoreThreshold) {
						logger.warnf("reCAPTCHA v3 score %s below threshold %s", score, scoreThreshold);
						return false;
					}
					Object returnedAction = json.get("action");
					if (!Validation.isBlank(expectedAction) && returnedAction != null && !expectedAction.equals(returnedAction)) {
						logger.warnf("reCAPTCHA action mismatch: expected '%s' but got '%s'", expectedAction, returnedAction);
						return false;
					}
				}
				return true;
			}
		} catch (Exception e) {
			ServicesLogger.LOGGER.recaptchaFailed(e);
		}
		return false;
	}
}
