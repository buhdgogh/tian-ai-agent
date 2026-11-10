package com.tianaiagent.infrastructure.dao;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.util.function.Consumer;

public final class MybatisSessions {
    private MybatisSessions() {}

    public static SqlSessionTemplate create(DataSource dataSource, Class<?>... mappers) {
        return create(dataSource, configuration -> {}, mappers);
    }

    public static SqlSessionTemplate create(DataSource dataSource, Consumer<MybatisConfiguration> customize, Class<?>... mappers) {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        customize.accept(configuration);

        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(GlobalConfigUtils.defaults().setBanner(false));
        try {
            var sessions = new SqlSessionTemplate(factory.getObject());
            for (Class<?> mapper : mappers) sessions.getConfiguration().addMapper(mapper);
            return sessions;
        } catch (Exception e) {
            throw new IllegalStateException("初始化 MyBatis-Plus 失败", e);
        }
    }
}
