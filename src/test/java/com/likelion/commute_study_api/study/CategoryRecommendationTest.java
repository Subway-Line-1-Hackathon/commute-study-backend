package com.likelion.commute_study_api.study;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.likelion.commute_study_api.study.StudyReadService.CategoryItem;
import com.likelion.commute_study_api.study.StudyReadService.State;
import static org.assertj.core.api.Assertions.*;

class CategoryRecommendationTest {
    final CategoryRecommendation policy=new CategoryRecommendation();

    @Test void mostRecentProgressTakesPriorityOverFirstUnstarted() {
        assertThat(policy.select(List.of(item(1,State.NOT_STARTED,null),
                item(2,State.IN_PROGRESS,"2026-11-08T01:00:00Z"),
                item(3,State.IN_PROGRESS,"2026-11-08T02:00:00Z")))).isEqualTo(3);
    }

    @Test void equalInstantsPreserveCategoryOrderRegardlessOfTimezoneOffset() {
        assertThat(policy.select(List.of(item(4,State.IN_PROGRESS,"2026-11-08T01:00:00Z"),
                item(2,State.IN_PROGRESS,"2026-11-08T10:00:00+09:00")))).isEqualTo(4);
    }

    @Test void preparingAndCompletedAreExcludedAndFirstUnstartedWins() {
        assertThat(policy.select(List.of(item(1,State.PREPARING,null),item(2,State.COMPLETED,null),
                item(5,State.NOT_STARTED,null),item(3,State.NOT_STARTED,null)))).isEqualTo(5);
        assertThat(policy.select(List.of(item(1,State.PREPARING,null),item(2,State.COMPLETED,null)))).isNull();
        assertThat(policy.select(List.of())).isNull();
    }

    @Test void missingLegacyTimeSortsLastAmongInProgress() {
        assertThat(policy.select(List.of(item(1,State.IN_PROGRESS,null),
                item(2,State.IN_PROGRESS,"2026-11-08T01:00:00Z")))).isEqualTo(2);
    }

    CategoryItem item(long id,State state,String time) {
        return new CategoryItem(id,1,"단원",1,2,null,0,null,time==null?null:OffsetDateTime.parse(time),state,
                state==State.PREPARING?"PREPARING":"AVAILABLE");
    }
}
