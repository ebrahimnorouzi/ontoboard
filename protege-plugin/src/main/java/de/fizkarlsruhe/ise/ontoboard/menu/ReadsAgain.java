package de.fizkarlsruhe.ise.ontoboard.menu;

import java.io.File;
import java.util.List;

/**
 * Whoever has the project open, asked to read again the files a run of ODK's make wrote:
 * Protege, in the menu's actions; a test's stand-in in the forms' tests.
 */
interface ReadsAgain {

    /**
     * @param written the files the run wrote; those that are not open are not read
     * @return what was read again, and a sentence for each file that was not and why
     */
    WhatARunWrote.Done readAgain(List<File> written);
}
