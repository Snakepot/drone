package cn.tedu.droneagent.config;

import cn.tedu.droneagent.bill.BillStore;
import cn.tedu.droneagent.budget.BudgetService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 预算执行装配（`REQ-AGENT-0028`）：只读账单，不写。 */
@Configuration
public class BudgetConfig {

    @Bean
    public BudgetService budgetService(BillStore billStore, BudgetProperties properties) {
        return new BudgetService(billStore, properties);
    }
}
