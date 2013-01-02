package com.totsp.server.enums;

//http://developer.android.com/guide/appendix/media-formats.html
public enum SupportedFileType {
   JPG, JPEG, GIF, PNG, BMP, WEBP, MP3, OGG, _3GP, M4A, AAC, MP4, MKV, WEBM, TXT;

   private static final String OH_3GP_YOU_SO_SPECIAL = "3gp";

   public static SupportedFileType getFromExt(String s) {
      if (s == null) {
         return null;
      }
      for (SupportedFileType t : values()) {
         if (t.name().equalsIgnoreCase(s)) {
            return t;
         } else if (s.equalsIgnoreCase(OH_3GP_YOU_SO_SPECIAL)) {
            return _3GP;
         }
      }
      return null;
   }

   public static SupportedFileType getFromString(String s) {
      if (s == null) {
         return null;
      }
      // if has a dot assume file name at END of path/string (lame, but works for this)
      if (s.indexOf(".") > -1) {
         String ext = s.substring(s.lastIndexOf(".") + 1, s.length());
         return SupportedFileType.getFromExt(ext);
      }
      return null;
   }
}
