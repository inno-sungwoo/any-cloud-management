package com.aipaas.anycloud.model.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponseDto<T> {
	private List<T> data;
	private int total;
	private int page;
	private int size;

	public static <T> PageResponseDto<T> of(List<T> data, int page, int size) {
		return PageResponseDto.<T>builder()
				.data(data)
				.total(data != null ? data.size() : 0)
				.page(page)
				.size(size)
				.build();
	}
}
