package com.rob.uiapi.controllers;

import java.sql.SQLException;
import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.rob.core.database.RoleSearchCriteria;
import com.rob.core.database.UserSearchCriteria;
import com.rob.core.fetch.RoleFetchHandler;
import com.rob.core.fetch.modules.FetchBuilder;
import com.rob.core.models.Role;
import com.rob.core.models.User;
import com.rob.core.models.enums.PropertiesEnum;
import com.rob.core.repositories.IRoleRepository;
import com.rob.core.repositories.IUserRepository;
import com.rob.core.services.IUserService;
import com.rob.core.utils.Properties;
import com.rob.core.utils.java.IntegerList;
import com.rob.security.jwt.JwtUtils;
import com.rob.security.payloads.request.LoginRequest;
import com.rob.security.payloads.request.SignupRequest;
import com.rob.security.payloads.response.JwtResponse;
import com.rob.security.payloads.response.MessageResponse;
import com.rob.uiapi.dto.mappers.UserMapper;
import com.rob.uiapi.dto.models.UserR;

@CrossOrigin(origins = "*", maxAge = 3600, allowCredentials = "false")
@RestController
@RequestMapping("/api/auth")
public class AuthRS {

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private PasswordEncoder encoder;

	@Autowired
	private JwtUtils jwtUtils;

	@Autowired
	private IUserService userService;

	@Autowired
	private IUserRepository userRepository;

	@Autowired
	private IRoleRepository roleRepository;

	@Autowired
	private UserMapper userMapper;
	
	private Properties mainProperties = new Properties(PropertiesEnum.MAIN_PROPERTIES.getName());
	private Properties macProperties = new Properties(PropertiesEnum.MAC_PROPERTIES.getName());

	/*
	 * @Autowired
	 * 
	 * @Qualifier("RoleServicesImpl") RoleServices roleServices;
	 */

	private final String TOKEN_COOKIE_NAME = Properties.TOKEN_COOKIE_NAME;
	private final String REMEMBER_COOKIE_NAME = Properties.REMEMBER_COOKIE_NAME;
	private final String PATH_COOKIES = Properties.PATH_COOKIES;

	private Cookie token_cookie = null;
	private Cookie remember_cookie = null;

	/**
	 * Used when a user try to login. It check if the params are ok and if they are,
	 * it creates and response with a JWT token.
	 * 
	 * @param loginRequest : Encapsulation of main parameters used to login
	 *                     (username and password).
	 * @param response
	 * @return JwtResponse with the token and the roles.
	 * @throws SQLException
	 */
	@PostMapping("/signin")
	public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest,
			HttpServletResponse response) throws SQLException {
		Authentication authentication;
		User user = null;

		try {

			authentication = authenticationManager.authenticate(
					new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword()));

			user = (User) userService.loadUserByUsername(loginRequest.getUsername());

		} catch (AuthenticationException e) {
			e.printStackTrace();
			return ResponseEntity.badRequest()
					.body(new MessageResponse("Attenzione! Le credenziali non sono corrette"));
		}

		SecurityContextHolder.getContext().setAuthentication(authentication);
		String token = jwtUtils.generateJwtToken(authentication, loginRequest.isRememberMe(), "" + user.getId());

		User userDetails = (User) authentication.getPrincipal();
		RoleSearchCriteria criteria = new RoleSearchCriteria();
		criteria.setUserId(userDetails.getId());
		FetchBuilder fetchBuilder = new FetchBuilder();
		fetchBuilder.addOption(RoleFetchHandler.FETCH_PERMISSIONS);
		criteria.setFetch(fetchBuilder.build());
		List<Role> roles = roleRepository.findByCriteria(criteria);

		if (setCookie(loginRequest.isRememberMe(), token, response)) {
			if (token_cookie != null)
				response.addCookie(token_cookie);
			if (remember_cookie != null)
				response.addCookie(remember_cookie);
			return ResponseEntity.ok(new JwtResponse(token, "" + userDetails.getId(), userDetails.getUsername(),
					userDetails.getEmail(), roles));
		}

		return ResponseEntity.badRequest().body(new MessageResponse("Errore: Qualcosa è andato storto!"));
	}

	/**
	 * Method used when a token is going to expire and a user chose to refresh it.
	 * 
	 * @param request
	 * @param response
	 * @return Bad request if something went wrong or an JwtResponse with the new
	 *         token
	 */
	@RequestMapping(value = "/refresh-token", method = RequestMethod.GET)
	public ResponseEntity<?> refreshAndGetAuthenticationToken(HttpServletRequest request,
			HttpServletResponse response) {

		// System.out.println("HEADERS: " + request.getHeader("authorization"));
		String authToken = request.getHeader(mainProperties.getProperty(PropertiesEnum.TOKEN_HEADER.getName()));
		boolean rememberMe = false;

		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			check: for (Cookie cookie : cookies) {
				if (cookie.getName().equals("rememberMe") && cookie.getValue().equals("true")) {
					rememberMe = true;
					break check;
				}
			}
		}

		if (authToken != null) {
			final String token = authToken.substring(7);

			if (jwtUtils.canTokenBeRefreshed(token)) {
				String refreshedToken = jwtUtils.refreshToken(token, rememberMe);

				response.setHeader(mainProperties.getProperty(PropertiesEnum.TOKEN_HEADER.getName()), refreshedToken);

				response.setHeader("exp", jwtUtils.getExpirationDateFromToken(refreshedToken).toString());

				if (setCookie(rememberMe, refreshedToken, response))
					return ResponseEntity.ok(new JwtResponse(refreshedToken));

			}

		}

		return ResponseEntity.badRequest().body(null);
	}

	/**
	 * Method used when a user want to sign up to the application. It also check the
	 * roles.
	 * 
	 * @param signUpRequest
	 * @param request
	 * @return badRequest if something went wrong or a ok response instead.
	 */
	@PostMapping("/signup")
	public ResponseEntity<?> registerUser(@RequestBody SignupRequest signUpRequest, HttpServletRequest request) {

		try {

			UserSearchCriteria criteria = new UserSearchCriteria();
			criteria.setUsername(signUpRequest.getUsername());
			Validate.isTrue(userRepository.findSingleByCriteria(criteria) == null, "Error: Username is already taken!");

			criteria = new UserSearchCriteria();
			criteria.setEmail(signUpRequest.getEmail());
			Validate.isTrue(userRepository.findSingleByCriteria(criteria) == null, "Error: Email is already in use!");

			// Create new user's account
			UserR userR = new UserR(signUpRequest.getUsername(), signUpRequest.getEmail(),
					encoder.encode(signUpRequest.getPassword()));

			User user = userMapper.map(userR);

			IntegerList strRoles = new IntegerList();
			strRoles.addAll(signUpRequest.getRoles());
			RoleSearchCriteria roleCriteria = new RoleSearchCriteria();
			roleCriteria.setIds(strRoles);
			List<Role> roles = roleRepository.findByCriteria(roleCriteria);

			user.setRoles(roles);

			// Save user into user collection in general DB for authentication
			user = userService.create(user);

			return ResponseEntity.ok(new MessageResponse("User registered successfully!"));

		} catch (Exception e) {
			e.printStackTrace();
			return ResponseEntity.badRequest().body(new MessageResponse("User registration failed!"));
		}
	}

	private boolean setCookie(boolean rememberMe, String token, HttpServletResponse response) {

		try {
			remember_cookie = new Cookie(REMEMBER_COOKIE_NAME, "" + rememberMe);
			remember_cookie.setSecure(false); // Set this to true if you're working through https
			remember_cookie.setHttpOnly(false);
			remember_cookie.setDomain(macProperties.getProperty(PropertiesEnum.HOST.getName()));
			remember_cookie.setPath(PATH_COOKIES); // global cookie accessible every where

			token_cookie = new Cookie(TOKEN_COOKIE_NAME, token);
			token_cookie.setSecure(false); // Set this to true if you're working through https
			token_cookie.setHttpOnly(false);
			token_cookie.setDomain(macProperties.getProperty(PropertiesEnum.HOST.getName()));
			token_cookie.setPath(PATH_COOKIES); // global cookie accessible every where

			if (!rememberMe) {
				token_cookie.setMaxAge(Integer.parseInt(mainProperties.getProperty(PropertiesEnum.JWT_EXPIRATION.getName())) / 1000);
				remember_cookie.setMaxAge(Integer.parseInt(mainProperties.getProperty(PropertiesEnum.JWT_EXPIRATION.getName())) / 1000);
			} else {
				token_cookie.setMaxAge((int) (Integer.parseInt(mainProperties.getProperty(PropertiesEnum.JWT_EXPIRATION_REMEMBER_ME.getName())) / 1000));
				remember_cookie.setMaxAge((int) (Integer.parseInt(mainProperties.getProperty(PropertiesEnum.JWT_EXPIRATION_REMEMBER_ME.getName())) / 1000));
			}

			response.addCookie(remember_cookie);
			response.addCookie(token_cookie);

			return true;
		} catch (Exception e) {
			System.out.println(e);
		}

		return false;
	}

	/*
	 * @PostMapping(value = "/getAll", produces = "application/json") public
	 * ResponseEntity<?> listAllUsr() {
	 * 
	 * ArrayList<User> utenti = userService.loadAllUsers(); if (utenti == null ||
	 * utenti.isEmpty()) { return ResponseEntity.badRequest().body(new
	 * MessageResponse("Non è stato trovato alcun utente!")); }
	 * 
	 * return new ResponseEntity<ArrayList<User>>(utenti, HttpStatus.OK);
	 * 
	 * }
	 * 
	 * @RequestMapping(value = "/{id}", method = RequestMethod.GET, produces =
	 * "application/json") public ResponseEntity<?> getUserById(@PathVariable("id")
	 * String Id) throws Exception {
	 * 
	 * Utente utente = userService.getUtenteModelById(Id);
	 * 
	 * if (utente == null) { return ResponseEntity.badRequest() .body(new
	 * MessageResponse("Non è stato trovato alcun utente con qusto id!")); }
	 * 
	 * return new ResponseEntity<Utente>(utente, HttpStatus.OK);
	 * 
	 * }
	 * 
	 * @RequestMapping(value = "/role/{id}", method = RequestMethod.GET, produces =
	 * "application/json") public ResponseEntity<?> getRoleyId(@PathVariable("id")
	 * String Id) throws Exception {
	 * 
	 * // Role role = roleServices.getRoleById(Id).get(); Role role =
	 * roleServices.findByName(ERole.ROLE_ADMIN).get();
	 * 
	 * if (role == null) { return ResponseEntity.badRequest() .body(new
	 * MessageResponse("Non è stato trovato alcun utente con qusto id!")); }
	 * 
	 * return new ResponseEntity<Role>(role, HttpStatus.OK);
	 * 
	 * }
	 */

}