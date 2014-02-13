/**
 * Copyright (c) 2013 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.datastore;

import com.cloudant.mazha.DocumentRevs;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * List of <code>DocumentRevs</code>. All the <code>DocumentRevs</code> are for the same document.
 * If the DocumentRevisionTree is a forest, some of the <code>DocumentRevs</code> might from different tree (case 2).
 * If two <code>DocumentRevs</code> are from the same tree, they are different branch of that tree (case 1).
 * <p>
 *
 * <pre>
 * Case 1: they are from same three.
 *   1 -> 2 -> 3
 *     \
 *     -> 2 -> 3*
 *
 * Case 2: they are from different trees
 *   1 -> 2  -> 3
 *
 *   1* -> 2* -> 3*
 * </pre>
 *
 * The list can be iterated in the order of minimum generation id (min-generation). Each
 * <code>DocumentRevs</code> has a list of revisions ids (aka revision history), and "start".
 * The "start" number is largest generation. So the min-generation is:
 * <pre>
 *   DocumentRevs.getRevisions().getStart() -> DocumentRevs.getRevisions().getIds().size() + 1.
 * </pre>
 * This is very important since it decides which <code>DocumentRevs</code> is inserted to db first.
 * <p>
 *
 * For <code>DocumentRevs</code> with the same "min-generation", the order is un-determined. This is
 * probably the case two document with same id/body are created in different database.
 */
public class DocumentRevsList implements Iterable<DocumentRevs> {

    private final List<DocumentRevs> documentRevsList;

    public DocumentRevsList(List<DocumentRevs> list) {
        Preconditions.checkNotNull(list, "DocumentRevs list must not be null");
        this.documentRevsList = new ArrayList<DocumentRevs>(list);

        // Order of the list decides which DocumentRevs is inserted first in bulk update.
        Collections.sort(this.documentRevsList, new Comparator<DocumentRevs>() {
            @Override
            public int compare(DocumentRevs o1, DocumentRevs o2) {
                return getMinGeneration(o1) - getMinGeneration(o2);
            }

            /**
             * Get the minimum generation id from the <code>DocumentRevs</code>
             * @see DocumentRevs
             */
            private int getMinGeneration(DocumentRevs o1) {
                return o1.getRevisions().getStart() - o1.getRevisions().getIds().size() + 1;
            }
        });
    }

    @Override
    public Iterator<DocumentRevs> iterator() {
        return this.documentRevsList.iterator();
    }

    public DocumentRevs get(int index) {
        return this.documentRevsList.get(index);
    }
}
