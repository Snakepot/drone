package cn.tedu.droneagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("cn.tedu.droneagent.config")
public class DroneAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DroneAgentApplication.class, args);
    }
}
