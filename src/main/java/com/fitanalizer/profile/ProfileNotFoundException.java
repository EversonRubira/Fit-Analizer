package com.fitanalizer.profile;

public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(String owner) {
        super("Profile não encontrado para owner: " + owner);
    }
}
