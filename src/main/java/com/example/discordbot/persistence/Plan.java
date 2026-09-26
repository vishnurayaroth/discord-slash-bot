package com.example.discordbot.persistence;

import java.util.List;

/** The planner's decision: the outcome, the priority flag, and the actions to record. */
public record Plan(String outcome, boolean priority, List<NewAction> actions) {}
