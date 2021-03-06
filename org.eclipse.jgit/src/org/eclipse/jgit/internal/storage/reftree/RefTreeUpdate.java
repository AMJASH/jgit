/*
 * Copyright (C) 2016, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.reftree;

import static org.eclipse.jgit.lib.Ref.Storage.LOOSE;
import static org.eclipse.jgit.lib.Ref.Storage.NEW;

import java.io.IOException;
import java.util.Collections;

import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/** Single reference update to {@link RefTreeDatabase}. */
class RefTreeUpdate extends RefUpdate {
	private final RefTreeDatabase refdb;
	private RevWalk rw;
	private RefTreeBatch batch;
	private Ref oldRef;

	RefTreeUpdate(RefTreeDatabase refdb, Ref ref) {
		super(ref);
		this.refdb = refdb;
		setCheckConflicting(false); // Done automatically by doUpdate.
	}

	/** {@inheritDoc} */
	@Override
	protected RefDatabase getRefDatabase() {
		return refdb;
	}

	/** {@inheritDoc} */
	@Override
	protected Repository getRepository() {
		return refdb.getRepository();
	}

	/** {@inheritDoc} */
	@Override
	protected boolean tryLock(boolean deref) throws IOException {
		rw = new RevWalk(getRepository());
		batch = new RefTreeBatch(refdb);
		batch.init(rw);
		oldRef = batch.exactRef(rw.getObjectReader(), getName());
		if (oldRef != null && oldRef.getObjectId() != null) {
			setOldObjectId(oldRef.getObjectId());
		} else if (oldRef == null && getExpectedOldObjectId() != null) {
			setOldObjectId(ObjectId.zeroId());
		}
		return true;
	}

	/** {@inheritDoc} */
	@Override
	protected void unlock() {
		batch = null;
		if (rw != null) {
			rw.close();
			rw = null;
		}
	}

	/** {@inheritDoc} */
	@Override
	protected Result doUpdate(Result desiredResult) throws IOException {
		return run(newRef(getName(), getNewObjectId()), desiredResult);
	}

	private Ref newRef(String name, ObjectId id)
			throws MissingObjectException, IOException {
		RevObject o = rw.parseAny(id);
		if (o instanceof RevTag) {
			RevObject p = rw.peel(o);
			return new ObjectIdRef.PeeledTag(LOOSE, name, id, p.copy());
		}
		return new ObjectIdRef.PeeledNonTag(LOOSE, name, id);
	}

	/** {@inheritDoc} */
	@Override
	protected Result doDelete(Result desiredResult) throws IOException {
		return run(null, desiredResult);
	}

	/** {@inheritDoc} */
	@Override
	protected Result doLink(String target) throws IOException {
		Ref dst = new ObjectIdRef.Unpeeled(NEW, target, null);
		SymbolicRef n = new SymbolicRef(getName(), dst);
		Result desiredResult = getRef().getStorage() == NEW
			? Result.NEW
			: Result.FORCED;
		return run(n, desiredResult);
	}

	private Result run(@Nullable Ref newRef, Result desiredResult)
			throws IOException {
		Command c = new Command(oldRef, newRef);
		batch.setRefLogIdent(getRefLogIdent());
		batch.setRefLogMessage(getRefLogMessage(), isRefLogIncludingResult());
		batch.execute(rw, Collections.singletonList(c));
		return translate(c.getResult(), desiredResult);
	}

	static Result translate(ReceiveCommand.Result r, Result desiredResult) {
		switch (r) {
		case OK:
			return desiredResult;

		case LOCK_FAILURE:
			return Result.LOCK_FAILURE;

		case NOT_ATTEMPTED:
			return Result.NOT_ATTEMPTED;

		case REJECTED_MISSING_OBJECT:
			return Result.IO_FAILURE;

		case REJECTED_CURRENT_BRANCH:
			return Result.REJECTED_CURRENT_BRANCH;

		case REJECTED_OTHER_REASON:
		case REJECTED_NOCREATE:
		case REJECTED_NODELETE:
		case REJECTED_NONFASTFORWARD:
		default:
			return Result.REJECTED;
		}
	}
}
