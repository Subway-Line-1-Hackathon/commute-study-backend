package com.likelion.commute_study_api.material;

import java.util.List;
import com.likelion.commute_study_api.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class MaterialUploadValidatorTest {
    private final MaterialUploadValidator validator = new MaterialUploadValidator();

    @ParameterizedTest
    @CsvSource({"file.pdf,application/pdf,PDF", "file.PDF,application/pdf,PDF", "file.png,image/png,PNG",
            "file.pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation,PPT",
            "photo.jpg,image/jpeg,JPG", "photo.JPEG,image/jpeg,JPG", "photo.jpeg,image/jpg,JPG"})
    void acceptsSupportedExtensionAndMime(String name,String mime,String type) {
        assertThat(validator.validate(List.of(new MockMultipartFile("file",name,mime,new byte[]{1})),"LECTURE").type()).isEqualTo(type);
    }

    @ParameterizedTest
    @CsvSource({"file.ppt,application/vnd.ms-powerpoint", "file.docx,application/octet-stream",
            "file.exe,application/pdf", "file.pdf,text/plain", "file.pptx,application/pdf", "file.png,application/pdf",
            "photo.jpg,image/png", "photo.png,image/jpeg", "photo.heic,image/heic", "photo.jpg,application/octet-stream"})
    void rejectsUnsupportedOrMismatchedFiles(String name,String mime) {
        assertThatThrownBy(() -> validator.validate(List.of(new MockMultipartFile("file",name,mime,new byte[]{1})),"LECTURE"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo(MaterialErrorCode.UNSUPPORTED_FILE_TYPE));
    }

    @Test
    void enforcesExactThirtyMiBBoundaryWithoutAllocatingLargeArray() {
        var exact = new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1}) {
            @Override public long getSize() { return MaterialUploadValidator.MAX_BYTES; }
        };
        assertThat(validator.validate(List.of(exact),"LECTURE")).isNotNull();
        var over = new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1}) {
            @Override public long getSize() { return MaterialUploadValidator.MAX_BYTES+1; }
        };
        assertThatThrownBy(() -> validator.validate(List.of(over),"LECTURE"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo(MaterialErrorCode.FILE_TOO_LARGE));
        assertThat(new com.likelion.commute_study_api.common.exception.GlobalExceptionHandler()
                .handleMaxUploadSize(new org.springframework.web.multipart.MaxUploadSizeExceededException(35L*1024*1024)).getBody().code())
                .isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void rejectsEmptyMultipleAndUnagreedPastExam() {
        var file=new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1});
        assertThatThrownBy(() -> validator.validate(List.of(file,file),"LECTURE")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(List.of(file),"PAST_EXAM")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate(List.of(new MockMultipartFile("file",new byte[0])),"LECTURE")).isInstanceOf(ApiException.class);
    }

    @Test
    void pngBatchAllowsTwentyAndRejectsTwentyOneMixedAndOversizedPart() {
        var png=new MockMultipartFile("file","page.png","image/png",new byte[]{1});
        assertThat(validator.validateBatch(java.util.Collections.nCopies(20,png),"LECTURE")).hasSize(20);
        assertThatThrownBy(() -> validator.validateBatch(java.util.Collections.nCopies(21,png),"LECTURE")).isInstanceOf(ApiException.class);
        var pdf=new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1});
        assertThatThrownBy(() -> validator.validateBatch(List.of(png,pdf),"LECTURE")).isInstanceOf(ApiException.class);
        var over=new MockMultipartFile("file","page.png","image/png",new byte[]{1}) {
            @Override public long getSize() { return MaterialUploadValidator.MAX_BYTES+1; }
        };
        assertThatThrownBy(() -> validator.validateBatch(List.of(png,over),"LECTURE"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo(MaterialErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void jpgAndMixedImageBatchesFollowTheSameTwentyLimit() {
        var png=new MockMultipartFile("file","page.png","image/png",new byte[]{1});
        var jpg=new MockMultipartFile("file","photo.jpg","image/jpeg",new byte[]{1});
        var pdf=new MockMultipartFile("file","file.pdf","application/pdf",new byte[]{1});
        assertThat(validator.validateBatch(java.util.Collections.nCopies(20,jpg),"LECTURE")).hasSize(20);
        var mixed=new java.util.ArrayList<org.springframework.web.multipart.MultipartFile>(java.util.Collections.nCopies(10,jpg));
        mixed.addAll(java.util.Collections.nCopies(10,png));
        assertThat(validator.validateBatch(mixed,"LECTURE")).hasSize(20);
        mixed.add(jpg);
        assertThatThrownBy(() -> validator.validateBatch(mixed,"LECTURE")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validateBatch(List.of(jpg,pdf),"LECTURE")).isInstanceOf(ApiException.class);
    }

    @Test
    void batchTypeIsJpgOnlyWhenEveryImageIsJpg() {
        var png=validator.validate(List.of(new MockMultipartFile("file","a.png","image/png",new byte[]{1})),"LECTURE");
        var jpg=validator.validate(List.of(new MockMultipartFile("file","b.jpg","image/jpeg",new byte[]{1})),"LECTURE");
        var pdf=validator.validate(List.of(new MockMultipartFile("file","c.pdf","application/pdf",new byte[]{1})),"LECTURE");
        assertThat(MaterialUploadValidator.batchType(List.of(jpg))).isEqualTo("JPG");
        assertThat(MaterialUploadValidator.batchType(List.of(jpg,jpg))).isEqualTo("JPG");
        assertThat(MaterialUploadValidator.batchType(List.of(jpg,png))).isEqualTo("PNG");
        assertThat(MaterialUploadValidator.batchType(List.of(png,jpg))).isEqualTo("PNG");
        assertThat(MaterialUploadValidator.batchType(List.of(png))).isEqualTo("PNG");
        assertThat(MaterialUploadValidator.batchType(List.of(pdf))).isEqualTo("PDF");
        assertThat(jpg.extension()).isEqualTo("jpg");
        assertThat(jpg.contentType()).isEqualTo("image/jpeg");
        assertThat(MaterialUploadValidator.isImage("JPG")).isTrue();
        assertThat(MaterialUploadValidator.isImage("PPT")).isFalse();
    }
}
