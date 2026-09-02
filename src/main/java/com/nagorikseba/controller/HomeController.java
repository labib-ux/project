package com.nagorikseba.controller;

import com.nagorikseba.complaint.domain.enums.Category;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.ui.Model;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("register", false);
        return "auth";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("register", true);
        return "auth";
    }

    @GetMapping("/citizen/complaint/new")
    public String newComplaint(Model model) {
        model.addAttribute("categories", Category.values());
        return "citizen/complaint-form";
    }
}