package fr.sii.controller.oauth;

import java.io.IOException;
import java.text.ParseException;
import java.util.Locale;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEException;

import fr.sii.config.auth.AuthSettings;
import fr.sii.domain.exception.BadRequestException;
import fr.sii.domain.recaptcha.ReCaptchaCheckerReponse;
import fr.sii.domain.token.Token;
import fr.sii.dto.user.LoginUser;
import fr.sii.dto.user.SignupUser;
import fr.sii.entity.User;
import fr.sii.service.GravatarUtils;
import fr.sii.service.auth.AuthUtils;
import fr.sii.service.auth.PasswordService;
import fr.sii.service.email.EmailingService;
import fr.sii.service.recaptcha.ReCaptchaChecker;
import fr.sii.service.user.UserService;

@RestController
@RequestMapping(value = "/auth", produces = "application/json; charset=utf-8")
public class AuthController {


    private static Logger logger = Logger.getLogger(AuthController.class.getName());

    private static final String CONFLICT_MSG_EMAIL = "There is already account associated with this email";

    private static final String ALREADY_VERIFIED = "This account is already verified";

    private static final String BAD_TOKEN = "Bad token";

    private static final String NOT_FOUND_MSG = "User not found";

    private static final String LOGING_ERROR_MSG = "Wrong email and/or password";

    private static final String UNLINK_ERROR_MSG = "Could not unlink %s account because it is your only sign-in method";

    @Autowired
    private UserService userService;

    @Autowired
    private EmailingService emailingService;

    @Autowired
    private AuthSettings authSettings;

    /**
     * Log user in
     *
     * @param httpServletResponse
     * @param httpServletRequest
     * @param user
     * @return
     * @throws JOSEException
     * @throws IOException
     */
    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public Token login(HttpServletResponse httpServletResponse, HttpServletRequest httpServletRequest, @RequestBody @Valid LoginUser user) throws JOSEException, IOException {
        User foundUser = userService.findByemail(user.getEmail());

        if (foundUser != null) {
            if (foundUser.getPassword() != null) {
                if (PasswordService.checkPassword(user.getPassword(), foundUser.getPassword())) {
                    return AuthUtils.createToken(httpServletRequest.getRemoteHost(), String.valueOf(foundUser.getId()), foundUser.isVerified());
                }
            }
        }

        httpServletResponse.getWriter().write(LOGING_ERROR_MSG);
        httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        httpServletResponse.getWriter().flush();
        httpServletResponse.getWriter().close();

        return null;
    }

    /**
     * Register new user
     *
     * @param httpServletResponse
     * @param httpServletRequest
     * @param signupUser
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/signup", method = RequestMethod.POST)
    public Token signup(HttpServletResponse httpServletResponse, HttpServletRequest
        httpServletRequest, @RequestBody @Valid SignupUser signupUser) throws Exception {

        ReCaptchaCheckerReponse rep = ReCaptchaChecker.checkReCaptcha(authSettings.getCaptchaSecret(), signupUser.getCaptcha());
        if (!rep.getSuccess()) {
            throw new BadRequestException("Bad captcha");
        }

        User foundUser = userService.findByemail(signupUser.getEmail());
        // Verify if user already exists
        if (foundUser != null) {
            httpServletResponse.setStatus(HttpServletResponse.SC_CONFLICT);
            httpServletResponse.getWriter().write(CONFLICT_MSG_EMAIL);
            httpServletResponse.getWriter().flush();
            httpServletResponse.getWriter().close();
            return null;
        }

        // Create new user
        User user = new User();
        // Setup verification token for email validation
        String verifyToken = RandomStringUtils.randomAlphanumeric(100);
        user.setVerifyToken(verifyToken);
        user.setPassword(PasswordService.hashPassword(signupUser.getPassword()));
        user.setEmail(signupUser.getEmail());

        String gravatarURL = GravatarUtils.getImageURL(signupUser.getEmail());
        user.setImageProfilURL(gravatarURL);

        // Save user
        User savedUser = userService.save(user);
        if (savedUser != null) {
            Locale userPreferredLocale = httpServletRequest.getLocale();
            emailingService.sendEmailValidation(savedUser, userPreferredLocale);
        }

        // Return JWT
        return AuthUtils.createToken(httpServletRequest.getRemoteHost(), String.valueOf(savedUser.getId()), savedUser.isVerified());
    }

    /**
     * Verify token for email validation
     *
     * @param res
     * @param req
     * @param id
     * @param verifyToken
     * @return
     * @throws JOSEException
     * @throws IOException
     */
    @RequestMapping(value = "/verify", method = RequestMethod.GET)
    public Token verify(HttpServletResponse res, HttpServletRequest req, @RequestParam("id") Integer
        id, @RequestParam("token") String verifyToken)
        throws JOSEException, IOException {
        Token token = null;

        // Search user
        User foundUser = userService.findById(id);
        if (foundUser == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(NOT_FOUND_MSG);
            res.getWriter().flush();
            res.getWriter().close();
            return token;
        } else {
            // Verify if account already verified
            if (foundUser.isVerified()) {
                res.setStatus(HttpServletResponse.SC_CONFLICT);
                res.getWriter().write(ALREADY_VERIFIED);
                res.getWriter().flush();
                res.getWriter().close();
                return token;
            }

            // Verify if token match
            if (foundUser.getVerifyToken().equals(verifyToken)) {
                foundUser.setVerified(true);
                foundUser.setVerifyToken(null);
                User savedUser = userService.save(foundUser);
                token = AuthUtils.createToken(req.getRemoteHost(), String.valueOf(savedUser.getId()), savedUser.isVerified());
                return token;
            } else {
                res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                res.getWriter().write(BAD_TOKEN);
                res.getWriter().flush();
                res.getWriter().close();
                return token;
            }
        }
    }

    /**
     * Unlink social provider
     *
     * @param res
     * @param req
     * @param provider
     * @throws JOSEException
     * @throws IOException
     * @throws ParseException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    @RequestMapping(value = "/unlink/{provider}", method = RequestMethod.GET)
    public void unlink(HttpServletResponse res, HttpServletRequest req, @PathVariable("provider") String provider)
        throws JOSEException, IOException, ParseException, NoSuchFieldException, IllegalAccessException {
        String authHeader = req.getHeader(AuthUtils.AUTH_HEADER_KEY);

        if (StringUtils.isBlank(authHeader)) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(NOT_FOUND_MSG);
            res.getWriter().flush();
            res.getWriter().close();
            return;
        }

        String subject = AuthUtils.getSubject(authHeader);
        User foundUser = userService.findById(Integer.parseInt(subject));

        if (foundUser == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(NOT_FOUND_MSG);
            res.getWriter().flush();
            res.getWriter().close();
            return;
        }

        // check that the user is not trying to unlink the only sign-in method
        if (foundUser.getSignInMethodCount() == 1) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(String.format(UNLINK_ERROR_MSG, provider));
            res.getWriter().flush();
            res.getWriter().close();
            return;
        }

        try {
            foundUser.setProviderId(User.Provider.valueOf(provider.toUpperCase()), null);
            userService.save(foundUser);
        } catch (final IllegalArgumentException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
    }
}
