package cn.tedu.droneagent.route;

/** 编排路径：由代码根据用户问题选择，而不是让模型自己纠结。 */
public enum TaskRoute {
    REACT,
    PLAN,
    SUBAGENT
}
