/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.mailbox.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.stream.Stream;

import org.apache.james.core.User;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.FetchGroupImpl;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxAnnotation;
import org.apache.james.mailbox.model.MailboxMetaData;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.apache.james.util.streams.Iterators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;

public class DefaultMailboxBackup implements MailboxBackup {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMailboxBackup.class);

    private static class MailAccountContent {
        private final MailboxWithAnnotations mailboxWithAnnotations;
        private final Stream<MessageResult> messages;

        MailAccountContent(MailboxWithAnnotations mailboxWithAnnotations, Stream<MessageResult> messages) {
            this.mailboxWithAnnotations = mailboxWithAnnotations;
            this.messages = messages;
        }

        public MailboxWithAnnotations getMailboxWithAnnotations() {
            return mailboxWithAnnotations;
        }

        public Stream<MessageResult> getMessages() {
            return messages;
        }
    }

    public DefaultMailboxBackup(MailboxManager mailboxManager, ArchiveService archiveService) {
        this.mailboxManager = mailboxManager;
        this.archiveService = archiveService;
    }

    @Override
    public void backupAccount(User user, OutputStream destination) throws IOException, MailboxException {
        MailboxSession session = mailboxManager.createSystemSession(user.asString());
        List<MailAccountContent> accountContents = getAccountContentForUser(session);
        List<MailboxWithAnnotations> mailboxes = accountContents.stream()
            .map(MailAccountContent::getMailboxWithAnnotations)
            .collect(Guavate.toImmutableList());

        Stream<MessageResult> messages = allMessagesForUser(accountContents);
        archive(mailboxes, messages, destination);
    }

    private final MailboxManager mailboxManager;
    private final ArchiveService archiveService;

    private Stream<MailAccountContent> getMailboxWithAnnotationsFromPath(MailboxSession session, MailboxPath path) {
        try {
            MessageManager messageManager =  mailboxManager.getMailbox(path, session);
            Mailbox mailbox = messageManager.getMailboxEntity();
            List<MailboxAnnotation> annotations = mailboxManager.getAllAnnotations(path, session);
            MailboxWithAnnotations mailboxWithAnnotations = new MailboxWithAnnotations(mailbox, annotations);
            Stream<MessageResult> messages = Iterators.toStream(messageManager.getMessages(MessageRange.all(), FetchGroupImpl.FULL_CONTENT, session));
            return Stream.of(new MailAccountContent(mailboxWithAnnotations, messages));
        } catch (MailboxException e) {
            LOGGER.error("Error while fetching Mailbox during backup", e);
            return Stream.empty();
        }
    }

    private List<MailAccountContent> getAccountContentForUser(MailboxSession session) throws MailboxException {
        MailboxQuery queryUser = MailboxQuery.builder().username(session.getUser().asString()).build();
        Stream<MailboxPath> paths = mailboxManager.search(queryUser, session).stream()
            .map(MailboxMetaData::getPath);
        List<MailAccountContent> mailboxes = paths
            .flatMap(path -> getMailboxWithAnnotationsFromPath(session, path))
            .collect(Guavate.toImmutableList());

        return mailboxes;
    }

    private void archive(List<MailboxWithAnnotations> mailboxes, Stream<MessageResult> messages, OutputStream destination) throws IOException {
        archiveService.archive(mailboxes, messages, destination);
    }

    private Stream<MessageResult> allMessagesForUser(List<MailAccountContent> mailboxes) {
        return mailboxes.stream().flatMap(MailAccountContent::getMessages);
    }

}