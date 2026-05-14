package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.impl.UVService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/stat")
public class StatController {

    @Resource
    private UVService uvService;

    @GetMapping("/uv")
    public Result getUV() {
        return Result.ok(uvService.getTodayUV());
    }
}