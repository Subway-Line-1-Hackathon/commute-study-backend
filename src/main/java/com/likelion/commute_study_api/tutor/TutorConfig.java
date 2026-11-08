package com.likelion.commute_study_api.tutor;

import java.time.Clock;
import java.util.concurrent.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class TutorConfig {
    @Bean public Clock tutorClock() { return Clock.systemUTC(); }
    @Bean(destroyMethod="close") public ExecutorService tutorExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tutor-",0).factory());
    }
}
