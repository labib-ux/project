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

    @GetMapping("/citizen/complaints/new")
    public String newComplaintPlural(Model model) {
        model.addAttribute("categories", Category.values());
        return "citizen/complaint-form";
    }

    @GetMapping("/login/authority")
    public String authorityLogin() {
        return "authority-login";
    }

    @GetMapping("/citizen/dashboard")
    public String citizenDashboard() {
        return "citizen/dashboard";
    }

    @GetMapping("/citizen/complaints/{referenceCode}")
    public String citizenComplaintDetail() {
        return "citizen/complaint-detail";
    }

    @GetMapping("/authority/dashboard")
    public String authorityDashboard() {
        return "authority/dashboard";
    }

    @GetMapping("/authority/queue")
    public String authorityQueue() {
        return "authority/queue";
    }

    @GetMapping("/authority/complaints/{referenceCode}")
    public String authorityComplaintDetail() {
        return "authority/complaint-detail";
    }

    @GetMapping("/admin")
    public String adminIndex() {
        return "admin/index";
    }
}