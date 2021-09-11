package com.rob.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.rob.dto.models.UserR;
import com.rob.models.User;
import com.rob.services.IUserService;

@CrossOrigin(origins = "*", maxAge = 3600, allowCredentials = "false")
@Controller
@RestController // This means that this class is a Controller
@RequestMapping(path = "/users") // This means URL's start with /demo (after Application path)
public class UserRS {

	@Autowired // This means to get the bean called userRepository. Which is auto-generated by
				// Spring, we will use it to handle the data
    private IUserService userService;

	@PostMapping(path = "/add") // Map ONLY POST Requests
	@ResponseBody
	public String addNewUser(@RequestBody String name) {
		// @ResponseBody means the returned String is the response, not a view name
		// @RequestParam means it is a parameter from the GET or POST request

		User n = new User();
		n.setName(name);
		n.setSurname("surname");
		n.setEmail("email");
		userService.save(n);
		return "Saved";
	}

	@GetMapping(path = "/all")
	public @ResponseBody Iterable<User> getAllUsers() {
		// This returns a JSON or XML with the users
		return userService.findAll();
	}
	
	@RequestMapping(value = "/{username}", method = RequestMethod.GET, produces = "application/json")
	public @ResponseBody UserR getAllUsers(@PathVariable("username") String username) {
		// This returns a JSON or XML with the users
		return (UserR) userService.loadUserByUsername(username);
	}

}
