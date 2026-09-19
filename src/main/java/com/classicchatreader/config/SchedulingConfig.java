package com.classicchatreader.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled jobs (first user: the FERPA term retention purge, BL-043.6). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
