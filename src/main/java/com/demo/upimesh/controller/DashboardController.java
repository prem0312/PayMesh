package com.demo.upimesh.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @Value("${paymesh.demo.public-dashboard:false}")
    private boolean publicDashboard;

    @Value("${paymesh.demo.writes-enabled:true}")
    private boolean demoWritesEnabled;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("publicDashboard", publicDashboard);
        model.addAttribute("writesEnabled", demoWritesEnabled && !publicDashboard);
        return "dashboard";
    }
}
