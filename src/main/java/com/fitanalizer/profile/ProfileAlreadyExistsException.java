package com.fitanalizer.profile;

public class ProfileAlreadyExistsException extends RuntimeException {

    public ProfileAlreadyExistsException(String owner) {
        super("Já existe um Profile para owner: " + owner);
    }
}
