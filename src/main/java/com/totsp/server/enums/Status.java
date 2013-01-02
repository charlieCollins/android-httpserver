package com.totsp.server.enums;


public enum Status {
   OK("200 OK"), PARTIAL_OK("216 Partial Content"), NOT_FOUND("404 Not Found"),
   NOT_ALLOWED("405 Method Not Allowed"), FORBIDDEN("403 Forbidden"), RANGE_INVALID(
            "416 Requested Range Not Satisfiable"), ERROR("500 Internal Server Error"), NOT_IMPL(
            "501 Not Implemented");

   private String desc;

   private Status(String desc) {
      this.desc = desc;
   }

   public String getDesc() {
      return this.desc;
   }
}

