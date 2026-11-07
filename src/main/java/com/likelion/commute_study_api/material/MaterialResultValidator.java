package com.likelion.commute_study_api.material;

import java.util.HashSet;
import com.likelion.commute_study_api.ai.material.MaterialProcessResponse;
import org.springframework.stereotype.Component;

@Component
public class MaterialResultValidator {
    public void validate(MaterialProcessResponse result) {
        if (result == null || result.pageCount() < 1 || result.pageCount() > 100
                || result.pages() == null || result.pages().size() != result.pageCount()
                || result.categories() == null || result.categories().isEmpty()) invalid();
        var pages = new HashSet<Integer>();
        for (var page : result.pages()) {
            if (page == null || page.page() < 1 || page.page() > result.pageCount()
                    || page.text() == null || !pages.add(page.page())) invalid();
        }
        var orders = new HashSet<Integer>();
        for (var category : result.categories()) {
            if (category == null || category.name() == null || category.name().isBlank()
                    || category.name().codePointCount(0, category.name().length()) > 100
                    || category.pageStart() < 1 || category.pageStart() > category.pageEnd()
                    || category.pageEnd() > result.pageCount() || !orders.add(category.order())
                    || category.summary() == null || category.summary().codePointCount(0, category.summary().length()) > 1500) invalid();
        }
    }
    private void invalid() { throw new IllegalArgumentException("Invalid AI material response"); }
}

