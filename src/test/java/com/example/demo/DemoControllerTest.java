package com.example.demo;

import com.example.demo.controller.DemoController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DemoController.class)
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void hello_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/hello"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("UP"))
               .andExpect(jsonPath("$.message").value("Hello from Demo App!"));
    }

    @Test
    void version_shouldReturnVersionInfo() throws Exception {
        mockMvc.perform(get("/api/version"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.app").value("demo-app"))
               .andExpect(jsonPath("$.version").value("1.0.0"));
    }

    @Test
    void echo_shouldReturnPostedBody() throws Exception {
        mockMvc.perform(post("/api/echo")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("{\"test\":\"value\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.echo.test").value("value"));
    }

    @Test
    void echo_withNoBody_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/echo")
                   .contentType(MediaType.APPLICATION_JSON))
               .andExpect(status().isOk());
    }
}
