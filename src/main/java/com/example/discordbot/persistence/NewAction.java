package com.example.discordbot.persistence;

/** One piece of work to record with an interaction: kind is reply, post or mirror. */
public record NewAction(String kind, String payload) {}
