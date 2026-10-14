package com.lux.backend.asset;

import com.lux.backend.common.ApiErrors;
import com.lux.backend.common.ApiException;
import com.lux.backend.user.UserService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetControllerTests {
    private final UUID owner = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final AssetService assets = mock(AssetService.class);
    private final AssetStore store = mock(AssetStore.class);
    private final UserService users = mock(UserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        when(assets.get(owner.toString(), id.toString())).thenReturn(new AssetRecord(
                id.toString(), owner.toString(), null, "hello.txt", "text/plain", 5, "unused", Instant.EPOCH));
        when(users.get(owner.toString())).thenReturn(
                new UserService.UserRecord(owner.toString(), "Owner", Instant.EPOCH));
        when(store.exists(owner, id)).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(controller()).setControllerAdvice(new ApiErrors()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/internal"})
    void contentStreamsFromStoreAtBothRoutes(String prefix) throws Exception {
        InputStream input = body("hello");
        when(store.open(owner, id, 0, 5)).thenReturn(input);

        mvc.perform(get(route(prefix)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(content().contentType("text/plain"))
                .andExpect(content().string("hello"));

        verify(input).close();
    }

    @ParameterizedTest
    @CsvSource({"bytes=1-3, 1, 3, ell", "bytes=-2, 3, 2, lo", "bytes=2-, 2, 3, llo",
            "bytes=3-99, 3, 2, lo", "bytes=-99, 0, 5, hello"})
    void rangesOpenOnlyRequestedBytes(String range, long offset, long length, String expected) throws Exception {
        InputStream input = body(expected);
        when(store.open(owner, id, offset, length)).thenReturn(input);

        mvc.perform(get(route("/api")).header("Range", range))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/5"))
                .andExpect(header().string("Content-Length", Long.toString(length)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(expected));

        verify(store).open(owner, id, offset, length);
        verify(input).close();
    }

    @Test
    void multipleRangesStreamSeparateStoreReadsWithMultipartFraming() throws Exception {
        InputStream first = body("he");
        InputStream second = body("o");
        when(store.open(owner, id, 0, 2)).thenReturn(first);
        when(store.open(owner, id, 4, 1)).thenReturn(second);

        MockHttpServletResponse response = mvc.perform(get(route("/api")).header("Range", "bytes=0-1,4-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Content-Range"))
                .andReturn().getResponse();

        MediaType type = MediaType.parseMediaType(response.getContentType());
        assertThat(type.isCompatibleWith(MediaType.parseMediaType("multipart/byteranges"))).isTrue();
        String boundary = type.getParameter("boundary");
        assertThat(boundary).isNotBlank();
        assertThat(response.getContentAsString()).contains(
                "--" + boundary + "\r\nContent-Type: text/plain\r\nContent-Range: bytes 0-1/5\r\n\r\nhe\r\n",
                "--" + boundary + "\r\nContent-Type: text/plain\r\nContent-Range: bytes 4-4/5\r\n\r\no\r\n")
                .endsWith("--" + boundary + "--");
        verify(first).close();
        verify(second).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=5-", "bytes=3-1", "bytes=-0", "items=0-1", "bytes=nonsense",
            "bytes=", "bytes=0-3,1-4"})
    void invalidRangesReturn416WithoutOpeningContent(String range) throws Exception {
        mvc.perform(get(route("/api")).header("Range", range))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */5"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        verify(store, never()).open(eq(owner), eq(id), anyLong(), anyLong());
    }

    @Test
    void headChecksExistenceWithoutOpeningBodyAndIgnoresRange() throws Exception {
        mvc.perform(head(route("/api")).header("Range", "bytes=1-3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().string(""));

        verify(store).exists(owner, id);
        verify(store, never()).open(eq(owner), eq(id), anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"/api, GET", "/internal, GET", "/api, HEAD", "/internal, HEAD"})
    void missingOwnershipHidesContentBeforeAnyStoreAccess(String prefix, String method) throws Exception {
        when(assets.get(owner.toString(), id.toString())).thenThrow(ApiException.notFound());

        mvc.perform(request(HttpMethod.valueOf(method), route(prefix)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(store);
    }

    @Test
    void missingObjectReturns404() throws Exception {
        when(store.open(owner, id, 0, 5)).thenThrow(new FileNotFoundException("Missing object"));

        mvc.perform(get(route("/api"))).andExpect(status().isNotFound());
    }

    @Test
    void headOfMissingObjectReturns404() throws Exception {
        when(store.exists(owner, id)).thenReturn(false);

        mvc.perform(head(route("/api"))).andExpect(status().isNotFound());

        verify(store, never()).open(eq(owner), eq(id), anyLong(), anyLong());
    }

    @Test
    void backendFailureIsNotReportedAsMissingContent() throws Exception {
        when(store.open(owner, id, 0, 5)).thenThrow(new IOException("Storage unavailable"));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> mvc.perform(get(route("/api"))))
                .withMessage("Storage unavailable");
    }

    @Test
    void inputClosesIfClientDisconnects() throws Exception {
        InputStream input = body("hello");
        when(store.open(owner, id, 0, 5)).thenReturn(input);
        MockMvc disconnected = MockMvcBuilders.standaloneSetup(controller()).addFilters((request, response, chain) ->
                chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) response) {
                    @Override
                    public ServletOutputStream getOutputStream() {
                        return new ServletOutputStream() {
                            @Override
                            public boolean isReady() { return true; }

                            @Override
                            public void setWriteListener(WriteListener listener) { }

                            @Override
                            public void write(int value) throws IOException {
                                throw new IOException("Client disconnected");
                            }
                        };
                    }
                })).build();

        MvcResult result = disconnected.perform(get(route("/api"))).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class)
                .hasRootCauseInstanceOf(IOException.class).hasRootCauseMessage("Client disconnected");

        verify(input).close();
    }

    private AssetController controller() {
        return new AssetController(assets, store, users);
    }

    private String route(String prefix) {
        return prefix + "/v1/users/" + owner + "/assets/" + id + "/content";
    }

    private static InputStream body(String content) {
        return spy(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
