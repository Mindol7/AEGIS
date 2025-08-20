package com.example.forensic.Repository;

import com.example.forensic.Entity.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class LogRepository {

    @Autowired
    private MongoTemplate mongoTemplate;

    // 동적으로 컬렉션 이름을 받아서 로그 저장
    public void save(Log log) {
        mongoTemplate.save(log);
    }



    // 동적으로 컬렉션 이름을 받아서 deviceId와 logType을 기준으로 로그 조회
    public List<Log> findByDeviceIdAndLogType(String deviceId, String logType) {
        Query query = new Query();
        query.addCriteria(Criteria.where("logType").is(logType));

        return mongoTemplate.find(query, Log.class);
    }




    // 특정 deviceId의 로그를 주어진 기간 내에서 조회

    public List<Log> findLogsWithinDuration(String deviceId, LocalDateTime startTime, LocalDateTime endTime) {
        Query query = new Query();
        query.addCriteria(Criteria.where("deviceId").is(deviceId)
                .and("createdAt").gte(startTime).lte(endTime));

        return mongoTemplate.find(query, Log.class);
    }

    public void deleteAll() {
        mongoTemplate.remove(new Query(), Log.class);
    }
}



