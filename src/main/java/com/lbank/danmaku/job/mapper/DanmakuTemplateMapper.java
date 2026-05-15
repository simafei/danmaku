package com.lbank.danmaku.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lbank.danmaku.job.domain.DanmakuTemplate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DanmakuTemplateMapper extends BaseMapper<DanmakuTemplate> {

    /**
     * 返回指定事件和语言的全量模板，供内存缓存使用。
     */
    @Select("SELECT * FROM danmaku_template WHERE matched_event = #{matchedEvent} AND language = #{language}")
    List<DanmakuTemplate> selectAll(
            @Param("matchedEvent") String matchedEvent,
            @Param("language") String language);

    /**
     * 查询指定事件已有的模板数量。
     */
    @Select("SELECT COUNT(*) FROM danmaku_template WHERE matched_event = #{matchedEvent} AND language = #{language}")
    int countByEventAndLanguage(
            @Param("matchedEvent") String matchedEvent,
            @Param("language") String language);
}
