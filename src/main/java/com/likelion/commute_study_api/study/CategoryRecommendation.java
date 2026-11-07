package com.likelion.commute_study_api.study;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import com.likelion.commute_study_api.study.StudyReadService.CategoryItem;
import com.likelion.commute_study_api.study.StudyReadService.State;

/** Issue #26 provisional policy; input is ordered by category.order then id. */
@Component
public class CategoryRecommendation {
    public Long select(List<CategoryItem> categories) {
        return categories.stream().filter(c -> "AVAILABLE".equals(c.questionStatus()) && c.state()==State.IN_PROGRESS)
                .sorted(Comparator.comparing((CategoryItem c) -> c.lastStudiedAt()==null ? null : c.lastStudiedAt().toInstant(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(CategoryItem::id).findFirst()
                .orElseGet(() -> categories.stream()
                        .filter(c -> "AVAILABLE".equals(c.questionStatus()) && c.state()==State.NOT_STARTED)
                        .map(CategoryItem::id).findFirst().orElse(null));
    }
}
