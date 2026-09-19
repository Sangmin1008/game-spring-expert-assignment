package com.gameexpert.ws.dto;

import java.util.List;
import lombok.Getter;

@Getter
public class OnlineUsersResponse {
    private final String type = "onlineUsers";
    private final List<String> users;
    private final int count;
    // TODO Lv 15: API 명세에 맞게 응답 필드와 생성자를 완성합니다.
    public OnlineUsersResponse(List<String> users, int count) {
        this.users = users;
        this.count = count;
    }
}
